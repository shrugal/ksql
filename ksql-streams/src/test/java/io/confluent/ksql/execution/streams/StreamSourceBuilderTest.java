/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.streams;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.plan.AbstractStreamSource;
import io.confluent.ksql.execution.plan.DefaultExecutionStepProperties;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.KStreamHolder;
import io.confluent.ksql.execution.plan.PlanBuilder;
import io.confluent.ksql.execution.plan.StreamSource;
import io.confluent.ksql.execution.plan.WindowedStreamSource;
import io.confluent.ksql.execution.timestamp.TimestampColumn;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.schema.ksql.ColumnRef;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.KeySerde;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.serde.WindowInfo;
import io.confluent.ksql.util.KsqlConfig;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology.AutoOffsetReset;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.kstream.ValueTransformerWithKeySupplier;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.SessionWindow;
import org.apache.kafka.streams.kstream.internals.TimeWindow;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


public class StreamSourceBuilderTest {

  private static final LogicalSchema SOURCE_SCHEMA = LogicalSchema.builder()
      .valueColumn(ColumnName.of("field1"), SqlTypes.STRING)
      .valueColumn(ColumnName.of("field2"), SqlTypes.BIGINT)
      .build();
  private static final Schema KEY_SCHEMA = SchemaBuilder.struct()
      .field("k1", Schema.OPTIONAL_STRING_SCHEMA)
      .build();
  private static final Struct KEY = new Struct(KEY_SCHEMA).put("k1", "foo");
  private static final SourceName ALIAS = SourceName.of("alias");
  private static final LogicalSchema SCHEMA =
      SOURCE_SCHEMA.withMetaAndKeyColsInValue().withAlias(ALIAS);

  private static final KsqlConfig KSQL_CONFIG = new KsqlConfig(ImmutableMap.of());

  private final Set<SerdeOption> SERDE_OPTIONS = new HashSet<>();
  private final PhysicalSchema PHYSICAL_SCHEMA = PhysicalSchema.from(SOURCE_SCHEMA, SERDE_OPTIONS);
  private static final String TOPIC_NAME = "topic";

  @Mock
  private QueryContext ctx;
  @Mock
  private KsqlQueryBuilder queryBuilder;
  @Mock
  private StreamsBuilder streamsBuilder;
  @Mock
  private KStream kStream;
  @Mock
  private FormatInfo keyFormatInfo;
  @Mock
  private WindowInfo windowInfo;
  @Mock
  private KeyFormat keyFormat;
  @Mock
  private ValueFormat valueFormat;
  @Mock
  private FormatInfo valueFormatInfo;
  @Mock
  private Serde<GenericRow> valueSerde;
  @Mock
  private KeySerde<Struct> keySerde;
  @Mock
  private KeySerde<Windowed<Struct>> windowedKeySerde;
  @Mock
  private ProcessorContext processorCtx;
  @Mock
  private ConsumedFactory consumedFactory;
  @Mock
  private StreamsFactories streamsFactories;
  @Mock
  private Consumed<Struct, GenericRow> consumed;
  @Mock
  private Consumed<Windowed<Struct>, GenericRow> consumedWindowed;
  @Captor
  private ArgumentCaptor<ValueMapper<GenericRow, GenericRow>> mapperCaptor;
  @Captor
  private ArgumentCaptor<ValueTransformerWithKeySupplier> transformSupplierCaptor;
  @Captor
  private ArgumentCaptor<TimestampExtractor> timestampExtractorCaptor;
  private Optional<AutoOffsetReset> offsetReset = Optional.of(AutoOffsetReset.EARLIEST);
  private final GenericRow row = new GenericRow(new LinkedList<>(ImmutableList.of("baz", 123)));
  private PlanBuilder planBuilder;

  private StreamSource streamSource;
  private WindowedStreamSource windowedStreamSource;

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Before
  @SuppressWarnings("unchecked")
  public void setup() {
    when(queryBuilder.getStreamsBuilder()).thenReturn(streamsBuilder);
    when(streamsBuilder.stream(anyString(), any(Consumed.class))).thenReturn(kStream);
    when(kStream.mapValues(any(ValueMapper.class))).thenReturn(kStream);
    when(kStream.transformValues(any(ValueTransformerWithKeySupplier.class))).thenReturn(kStream);
    when(queryBuilder.buildValueSerde(any(), any(), any())).thenReturn(valueSerde);
    when(queryBuilder.getKsqlConfig()).thenReturn(KSQL_CONFIG);
    when(valueFormat.getFormatInfo()).thenReturn(valueFormatInfo);
    when(processorCtx.timestamp()).thenReturn(456L);
    when(keyFormat.getFormatInfo()).thenReturn(keyFormatInfo);
    when(streamsFactories.getConsumedFactory()).thenReturn(consumedFactory);

    planBuilder = new KSPlanBuilder(
        queryBuilder,
        mock(SqlPredicateFactory.class),
        mock(AggregateParamsFactory.class),
        streamsFactories
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldApplyCorrectTransformationsToSourceStream() {
    // Given:
    givenUnwindowedSource();

    // When:
    final KStreamHolder<?> builtKstream = streamSource.build(planBuilder);

    // Then:
    assertThat(builtKstream.getStream(), is(kStream));
    final InOrder validator = inOrder(streamsBuilder, kStream);
    validator.verify(streamsBuilder).stream(TOPIC_NAME, consumed);
    validator.verify(kStream, never()).mapValues(any(ValueMapper.class));
    validator.verify(kStream).transformValues(any(ValueTransformerWithKeySupplier.class));
    verify(consumedFactory).create(keySerde, valueSerde);
    verify(consumed).withTimestampExtractor(any());
    verify(consumed).withOffsetResetPolicy(AutoOffsetReset.EARLIEST);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldBuildStreamWithCorrectTimestampExtractor() {
    // Given:
    givenUnwindowedSource();
    final ConsumerRecord<Object, Object> record = mock(ConsumerRecord.class);
    when(record.value()).thenReturn(new GenericRow("123", 456L));

    // When:
    streamSource.build(planBuilder);

    // Then:
    verify(consumed).withTimestampExtractor(timestampExtractorCaptor.capture());
    final TimestampExtractor extractor = timestampExtractorCaptor.getValue();
    assertThat(extractor.extract(record, 789), is(456L));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldReturnCorrectSchemaForUnwindowedSource() {
    // Given:
    givenUnwindowedSource();

    // When:
    final KStreamHolder<?> builtKstream = streamSource.build(planBuilder);

    // Then:
    assertThat(builtKstream.getSchema(), is(SCHEMA));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldNotBuildWithOffsetResetIfNotProvided() {
    // Given:
    offsetReset = Optional.empty();
    givenUnwindowedSource();

    // When
    streamSource.build(planBuilder);

    // Then:
    verify(consumedFactory).create(keySerde, valueSerde);
    verify(consumed).withTimestampExtractor(any());
    verifyNoMoreInteractions(consumed, consumedFactory);
  }

  @Test
  public void shouldBuildSourceValueSerdeCorrectly() {
    // Given:
    givenUnwindowedSource();

    // When:
    streamSource.build(planBuilder);

    // Then:
    verify(queryBuilder).buildValueSerde(valueFormatInfo, PHYSICAL_SCHEMA, ctx);
  }

  @Test
  public void shouldBuildSourceKeySerdeCorrectly() {
    // Given:
    givenWindowedSource();

    // When:
    windowedStreamSource.build(planBuilder);

    // Then:
    verify(queryBuilder).buildKeySerde(
        keyFormatInfo,
        windowInfo,
        PhysicalSchema.from(SOURCE_SCHEMA, SERDE_OPTIONS),
        ctx
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldReturnCorrectSchemaForWindowedSource() {
    // Given:
    givenWindowedSource();

    // When:
    final KStreamHolder<?> builtKstream = windowedStreamSource.build(planBuilder);

    // Then:
    assertThat(builtKstream.getSchema(), is(SCHEMA));
  }

  @Test
  public void shouldThrowOnMultiFieldKey() {
    // Given:
    givenUnwindowedSource();
    final StreamSource streamSource = new StreamSource(
        new DefaultExecutionStepProperties(SCHEMA, ctx),
        TOPIC_NAME,
        Formats.of(keyFormat, valueFormat, SERDE_OPTIONS),
        Optional.empty(),
        offsetReset,
        LogicalSchema.builder()
            .keyColumn(ColumnName.of("f1"), SqlTypes.INTEGER)
            .keyColumn(ColumnName.of("f2"), SqlTypes.BIGINT)
            .valueColumns(SCHEMA.value())
            .build(),
        ALIAS
    );

    // Then:
    expectedException.expect(instanceOf(IllegalStateException.class));

    // When:
    streamSource.build(planBuilder);
  }

  @Test
  public void shouldAddRowTimeAndRowKeyColumns() {
    // Given:
    givenUnwindowedSource();
    final ValueTransformerWithKey<Struct, GenericRow, GenericRow> transformer =
        getTransformerFromStreamSource(streamSource);

    // When:
    final GenericRow withTimestamp = transformer.transform(KEY, row);

    // Then:
    assertThat(withTimestamp, equalTo(new GenericRow(456L, "foo", "baz", 123)));
  }

  @Test
  public void shouldHandleNullKey() {
    // Given:
    givenUnwindowedSource();
    final ValueTransformerWithKey<Struct, GenericRow, GenericRow> transformer =
        getTransformerFromStreamSource(streamSource);

    final Struct nullKey = new Struct(KEY_SCHEMA);

    // When:
    final GenericRow withTimestamp = transformer.transform(nullKey, row);

    // Then:
    assertThat(withTimestamp, equalTo(new GenericRow(456L, null, "baz", 123)));
  }

  @Test
  public void shouldAddRowTimeAndTimeWindowedRowKeyColumns() {
    // Given:
    givenWindowedSource();
    final ValueTransformerWithKey<Windowed<Struct>, GenericRow, GenericRow> transformer =
        getTransformerFromStreamSource(windowedStreamSource);

    final Windowed<Struct> key = new Windowed<>(
        KEY,
        new TimeWindow(10L, 20L)
    );

    // When:
    final GenericRow withTimestamp = transformer.transform(key, row);

    // Then:
    assertThat(withTimestamp,
        equalTo(new GenericRow(456L, "foo : Window{start=10 end=-}", "baz", 123)));
  }

  @Test
  public void shouldAddRowTimeAndSessionWindowedRowKeyColumns() {
    // Given:
    givenWindowedSource();
    final ValueTransformerWithKey<Windowed<Struct>, GenericRow, GenericRow> transformer =
        getTransformerFromStreamSource(windowedStreamSource);

    final Windowed<Struct> key = new Windowed<>(
        KEY,
        new SessionWindow(10L, 20L)
    );

    // When:
    final GenericRow withTimestamp = transformer.transform(key, row);

    // Then:
    assertThat(withTimestamp,
        equalTo(new GenericRow(456L, "foo : Window{start=10 end=20}", "baz", 123)));
  }

  @Test
  public void shouldUseCorrectSerdeForWindowedKey() {
    // Given:
    givenWindowedSource();

    // When:
    windowedStreamSource.build(planBuilder);

    // Then:
    verify(queryBuilder).buildKeySerde(
        keyFormatInfo,
        windowInfo,
        PhysicalSchema.from(SOURCE_SCHEMA, SERDE_OPTIONS),
        ctx
    );
  }

  @Test
  public void shouldUseCorrectSerdeForNonWindowedKey() {
    // Given:
    givenUnwindowedSource();

    // When:
    streamSource.build(planBuilder);

    // Then:
    verify(queryBuilder).buildKeySerde(
        keyFormatInfo,
        PhysicalSchema.from(SOURCE_SCHEMA, SERDE_OPTIONS),
        ctx
    );
  }

  @Test
  public void shouldReturnCorrectSerdeFactory() {
    // Given:
    givenUnwindowedSource();

    // When:
    final KStreamHolder<?> stream = streamSource.build(planBuilder);

    // Then:
    reset(queryBuilder);
    stream.getKeySerdeFactory().buildKeySerde(keyFormat, PHYSICAL_SCHEMA, ctx);
    verify(queryBuilder).buildKeySerde(keyFormatInfo, PHYSICAL_SCHEMA, ctx);
  }

  @Test
  public void shouldReturnCorrectSerdeFactoryForWindowedSource() {
    // Given:
    givenWindowedSource();

    // When:
    final KStreamHolder<?> stream = windowedStreamSource.build(planBuilder);

    // Then:
    reset(queryBuilder);
    stream.getKeySerdeFactory().buildKeySerde(keyFormat, PHYSICAL_SCHEMA, ctx);
    verify(queryBuilder).buildKeySerde(keyFormatInfo, windowInfo, PHYSICAL_SCHEMA, ctx);
  }


  @SuppressWarnings("unchecked")
  private <K> ValueTransformerWithKey<K, GenericRow, GenericRow> getTransformerFromStreamSource(
      final AbstractStreamSource<?> streamSource
  ) {
    streamSource.build(planBuilder);
    verify(kStream).transformValues(transformSupplierCaptor.capture());
    final ValueTransformerWithKey transformer = transformSupplierCaptor.getValue().get();
    transformer.init(processorCtx);
    return transformer;
  }

  private void givenWindowedSource() {
    when(keyFormat.isWindowed()).thenReturn(true);
    when(keyFormat.getWindowInfo()).thenReturn(Optional.of(windowInfo));
    when(queryBuilder.buildKeySerde(any(), any(), any(), any())).thenReturn(windowedKeySerde);
    givenConsumed(consumedWindowed, windowedKeySerde);
    windowedStreamSource = new WindowedStreamSource(
        new DefaultExecutionStepProperties(SCHEMA, ctx),
        TOPIC_NAME,
        Formats.of(keyFormat, valueFormat, SERDE_OPTIONS),
        Optional.of(
            new TimestampColumn(
                ColumnRef.withoutSource(ColumnName.of("field2")),
                Optional.empty()
            )
        ),
        offsetReset,
        SOURCE_SCHEMA,
        ALIAS
    );
  }

  private void givenUnwindowedSource() {
    when(keyFormat.getWindowInfo()).thenReturn(Optional.empty());
    when(queryBuilder.buildKeySerde(any(), any(), any())).thenReturn(keySerde);
    givenConsumed(consumed, keySerde);
    streamSource = new StreamSource(
        new DefaultExecutionStepProperties(SCHEMA, ctx),
        TOPIC_NAME,
        Formats.of(keyFormat, valueFormat, SERDE_OPTIONS),
        Optional.of(
            new TimestampColumn(
                ColumnRef.withoutSource(ColumnName.of("field2")),
                Optional.empty()
            )
        ),
        offsetReset,
        SOURCE_SCHEMA,
        ALIAS
    );
  }

  private <K> void givenConsumed(final Consumed<K, GenericRow> consumed, final Serde<K> keySerde) {
    when(consumedFactory.create(keySerde, valueSerde)).thenReturn(consumed);
    when(consumed.withTimestampExtractor(any())).thenReturn(consumed);
    when(consumed.withOffsetResetPolicy(any())).thenReturn(consumed);
  }
}