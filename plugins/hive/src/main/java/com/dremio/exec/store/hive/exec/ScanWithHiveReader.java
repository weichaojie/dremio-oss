/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.hive.exec;

import static com.dremio.exec.store.hive.HiveUtilities.addProperties;
import static com.dremio.exec.store.hive.HiveUtilities.createSerDe;
import static com.dremio.exec.store.hive.HiveUtilities.getInputFormatClass;
import static com.dremio.exec.store.hive.HiveUtilities.getStructOI;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.RCFileInputFormat;
import org.apache.hadoop.hive.ql.io.avro.AvroContainerInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSplit;
import org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.compress.snappy.SnappyDecompressor;
import org.apache.hadoop.io.compress.zlib.ZlibDecompressor;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.NativeCodeLoader;
import org.apache.orc.OrcConf;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.store.EmptyRecordReader;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.ScanFilter;
import com.dremio.exec.store.dfs.implicit.CompositeReaderConfig;
import com.dremio.exec.store.hive.HivePluginOptions;
import com.dremio.exec.store.hive.HiveUtilities;
import com.dremio.hive.proto.HiveReaderProto.HiveSplitXattr;
import com.dremio.hive.proto.HiveReaderProto.HiveTableXattr;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.op.scan.ScanOperator;
import com.dremio.sabot.op.spi.ProducerOperator;
import com.dremio.service.namespace.dataset.proto.DatasetSplit;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterators;

/**
 * Helper class for {@link HiveScanBatchCreator} to create a {@link ProducerOperator} that uses readers provided by
 * Hive.
 */
class ScanWithHiveReader {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ScanWithHiveReader.class);

  /**
   * Use different classes for different Hive native formats:
   * ORC, AVRO, RCFFile, Text and Parquet.
   * If input format is none of them falls to default reader.
   */
  private static final Map<String, Class<? extends HiveAbstractReader>> readerMap = new HashMap<>();
  static {
    readerMap.put(OrcInputFormat.class.getCanonicalName(), HiveOrcReader.class);
    readerMap.put(AvroContainerInputFormat.class.getCanonicalName(), HiveAvroReader.class);
    readerMap.put(RCFileInputFormat.class.getCanonicalName(), HiveRCFileReader.class);
    readerMap.put(MapredParquetInputFormat.class.getCanonicalName(), HiveParquetReader.class);
    readerMap.put(TextInputFormat.class.getCanonicalName(), HiveTextReader.class);
  }

  private static final boolean isNativeZlibLoaded;
  static {
    boolean isLoaded;
    try {
      Method m = ZlibDecompressor.class.getDeclaredMethod("isNativeZlibLoaded");
      m.setAccessible(true);
      isLoaded = (boolean) m.invoke(null);
    } catch (ReflectiveOperationException e) {
      // ignore
      logger.warn("Cannot detect if Zlib native codec is properly loaded", e);
      isLoaded = true;
    }
    isNativeZlibLoaded = isLoaded;
  }

  private static Class<? extends HiveAbstractReader> getNativeReaderClass(Optional<String> formatName,
      OptionManager options, Configuration configuration, boolean mixedSchema, boolean isTransactional) {
    if (!formatName.isPresent()) {
      return HiveDefaultReader.class;
    }

    Class<? extends HiveAbstractReader> readerClass = readerMap.get(formatName.get());
    if (readerClass == HiveOrcReader.class) {
      // Validate reader
      if (OrcConf.USE_ZEROCOPY.getBoolean(configuration)) {
        if (!NativeCodeLoader.isNativeCodeLoaded()) {
          throw UserException.dataReadError()
              .message("Hadoop native library is required for Hive ORC data, but is not loaded").build(logger);
        }
        // TODO: find a way to access compression codec information?
        if (!SnappyDecompressor.isNativeCodeLoaded()) {
          throw UserException.dataReadError()
            .message("Snappy native library is required for Hive ORC data, but is not loaded").build(logger);
        }

        if (!isNativeZlibLoaded) {
          throw UserException
          .dataReadError()
          .message("Zlib native library is required for Hive ORC data, but is not loaded")
          .build(logger);
        }
      }

      if (options.getOption(HivePluginOptions.HIVE_ORC_READER_VECTORIZE) && !mixedSchema && !isTransactional) {
        // We don't use vectorized ORC reader if there is a schema change between table and partitions or the table is
        // a transactional Hive table
        return HiveORCVectorizedReader.class;
      }
    }

    if (readerClass == null) {
      return HiveDefaultReader.class;
    }

    return readerClass;
  }

  private static Constructor<? extends HiveAbstractReader> getNativeReaderCtor(Class<? extends HiveAbstractReader> clazz)
      throws NoSuchMethodException {
    return clazz.getConstructor(HiveTableXattr.class, DatasetSplit.class, List.class, OperatorContext.class,
        JobConf.class, SerDe.class, StructObjectInspector.class, SerDe.class, StructObjectInspector.class,
        ScanFilter.class);
  }

  static ProducerOperator createProducer(
      final HiveConf hiveConf,
      final FragmentExecutionContext fragmentExecContext,
      final OperatorContext context,
      final HiveSubScan config,
      final HiveTableXattr tableAttr,
      final CompositeReaderConfig compositeReader){

    if(config.getSplits().isEmpty()) {
      return new ScanOperator(fragmentExecContext.getSchemaUpdater(), config, context, Iterators.singletonIterator(new EmptyRecordReader()));
    }

    final JobConf baseJobConf = new JobConf(hiveConf);
    final Properties tableProperties = new Properties();
    addProperties(baseJobConf, tableProperties, HiveReaderProtoUtil.getTableProperties(tableAttr));
    final boolean isTransactional = AcidUtils.isTablePropertyTransactional(baseJobConf);

    final boolean isPartitioned = config.getPartitionColumns() != null && config.getPartitionColumns().size() > 0;
    final Optional<String> tableInputFormat = HiveReaderProtoUtil.getTableInputFormat(tableAttr);

    Iterable<RecordReader> readers = null;

    try {
      final UserGroupInformation currentUGI = UserGroupInformation.getCurrentUser();
      readers = FluentIterable.from(config.getSplits()).transform(new Function<DatasetSplit, RecordReader>(){

        @Override
        public RecordReader apply(final DatasetSplit split) {
          return currentUGI.doAs(new PrivilegedAction<RecordReader>() {
            @Override
            public RecordReader run() {
              try {
                final HiveSplitXattr splitAttr = HiveSplitXattr.parseFrom(split.getExtendedProperty().toByteArray());
                final JobConf jobConf = new JobConf(baseJobConf);

                final SerDe tableSerDe = createSerDe(jobConf, HiveReaderProtoUtil.getTableSerializationLib(tableAttr).get(),
                    tableProperties);
                final StructObjectInspector tableOI = getStructOI(tableSerDe);
                final SerDe partitionSerDe;
                final StructObjectInspector partitionOI;

                boolean hasDeltas = false;
                if (isTransactional) {
                  OrcSplit orcSplit = (OrcSplit) HiveUtilities.deserializeInputSplit(splitAttr.getInputSplit());
                  hasDeltas = hasDeltas(orcSplit);
                }

                final Class<? extends HiveAbstractReader> tableReaderClass =
                  getNativeReaderClass(tableInputFormat, context.getOptions(), hiveConf, false, isTransactional && hasDeltas);

                final Constructor<? extends HiveAbstractReader> tableReaderCtor = getNativeReaderCtor(tableReaderClass);

                Constructor<? extends HiveAbstractReader> readerCtor = tableReaderCtor;
                // It is possible to for a partition to have different input format than table input format.
                if (isPartitioned) {
                  final Properties partitionProperties = new Properties();
                  // First add table properties and then add partition properties. Partition properties override table properties.
                  addProperties(jobConf, partitionProperties, HiveReaderProtoUtil.getTableProperties(tableAttr));
                  addProperties(jobConf, partitionProperties,
                      HiveReaderProtoUtil.getPartitionProperties(tableAttr, splitAttr.getPartitionId())
                  );

                  partitionSerDe =
                      createSerDe(jobConf,
                          HiveReaderProtoUtil.getPartitionSerializationLib(tableAttr, splitAttr.getPartitionId()).get(),
                          partitionProperties
                      );
                  partitionOI = getStructOI(partitionSerDe);
                  jobConf.setInputFormat(getInputFormatClass(jobConf, tableAttr, splitAttr));

                  final Optional<String> partitionInputFormat =
                      HiveReaderProtoUtil.getPartitionInputFormat(tableAttr, splitAttr.getPartitionId());
                  final boolean mixedSchema = !tableOI.equals(partitionOI);
                  if (!partitionInputFormat.equals(tableInputFormat) || mixedSchema || isTransactional && hasDeltas) {
                    final Class<? extends HiveAbstractReader> partitionReaderClass = getNativeReaderClass(
                        partitionInputFormat, context.getOptions(), jobConf, mixedSchema, isTransactional);
                    readerCtor = getNativeReaderCtor(partitionReaderClass);
                  }
                } else {
                  partitionSerDe = null;
                  partitionOI = null;
                  jobConf.setInputFormat(getInputFormatClass(jobConf, tableAttr, null));
                }

                final RecordReader innerReader = readerCtor.newInstance(tableAttr, split,
                    compositeReader.getInnerColumns(), context, jobConf, tableSerDe, tableOI, partitionSerDe,
                    partitionOI, config.getFilter());

                return compositeReader.wrapIfNecessary(context.getAllocator(), innerReader, split);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          });
        }});
      return new ScanOperator(fragmentExecContext.getSchemaUpdater(), config, context, readers.iterator());
    } catch (Exception e) {
      AutoCloseables.close(e, readers);
      throw Throwables.propagate(e);
    }
  }

  private static boolean hasDeltas(OrcSplit orcSplit) throws IOException {
    final Path path = orcSplit.getPath();
    final Path root;

    // If the split has a base, extract the base file size, bucket and root path info.
    if (orcSplit.hasBase()) {
      if (orcSplit.isOriginal()) {
        root = path.getParent();
      } else {
        root = path.getParent().getParent();
      }
    } else {
      root = path;
    }

    final Path[] deltas = AcidUtils.deserializeDeltas(root, orcSplit.getDeltas());
    return deltas.length > 0;
  }
}
