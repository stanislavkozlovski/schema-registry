/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.kafka.formatter;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaAvroDeserializer;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import kafka.common.MessageFormatter;

/**
 * Example
 * To use AvroMessageFormatter, first make sure that Zookeeper, Kafka and schema registry server are
 * all started. Second, make sure the jar for AvroMessageFormatter and its dependencies are included
 * in the classpath of kafka-console-consumer.sh. Then run the following command.
 *
 * <p>1. To read only the value of the messages in JSON
 * bin/kafka-console-consumer.sh --consumer.config config/consumer.properties --topic t1 \
 *   --zookeeper localhost:2181 --formatter io.confluent.kafka.formatter.AvroMessageFormatter \
 *   --property schema.registry.url=http://localhost:8081
 *
 * <p>2. To read both the key and the value of the messages in JSON
 * bin/kafka-console-consumer.sh --consumer.config config/consumer.properties --topic t1 \
 *   --zookeeper localhost:2181 --formatter io.confluent.kafka.formatter.AvroMessageFormatter \
 *   --property schema.registry.url=http://localhost:8081 \
 *   --property print.key=true
 *
 */
public class AvroMessageFormatter extends AbstractKafkaAvroDeserializer
    implements MessageFormatter {

  private final EncoderFactory encoderFactory = EncoderFactory.get();
  private static final byte[] NULL_BYTES = "null".getBytes(StandardCharsets.UTF_8);
  private boolean printKey = false;
  private byte[] keySeparator = "\t".getBytes(StandardCharsets.UTF_8);
  private byte[] lineSeparator = "\n".getBytes(StandardCharsets.UTF_8);
  private Deserializer keyDeserializer;

  /**
   * Constructor needed by kafka console consumer.
   */
  public AvroMessageFormatter() {
  }

  /**
   * For testing only.
   */
  AvroMessageFormatter(
      SchemaRegistryClient schemaRegistryClient,
      boolean printKey,
      Deserializer keyDeserializer
  ) {
    this.schemaRegistry = schemaRegistryClient;
    this.printKey = printKey;
    this.keyDeserializer = keyDeserializer;
  }

  @Override
  public void init(Properties props) {
    if (props == null) {
      throw new ConfigException("Missing schema registry url!");
    }
    String url = props.getProperty(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG);
    if (url == null) {
      throw new ConfigException("Missing schema registry url!");
    }

    Map<String, Object> originals = getPropertiesMap(props);
    schemaRegistry = new CachedSchemaRegistryClient(
        url, AbstractKafkaAvroSerDeConfig.MAX_SCHEMAS_PER_SUBJECT_DEFAULT, originals);

    if (props.containsKey("print.key")) {
      printKey = props.getProperty("print.key").trim().toLowerCase().equals("true");
    }
    if (props.containsKey("key.separator")) {
      keySeparator = props.getProperty("key.separator").getBytes(StandardCharsets.UTF_8);
    }
    if (props.containsKey("line.separator")) {
      lineSeparator = props.getProperty("line.separator").getBytes(StandardCharsets.UTF_8);
    }
    if (props.containsKey("key.deserializer")) {
      try {
        keyDeserializer =
            (Deserializer)Class.forName((String) props.get("key.deserializer")).newInstance();
      } catch (Exception e) {
        throw new ConfigException("Error initializing Key deserializer", e);
      }
    }
  }

  private Map<String, Object> getPropertiesMap(Properties props) {
    Map<String,Object> originals = new HashMap<>();
    for (final String name: props.stringPropertyNames()) {
      originals.put(name, props.getProperty(name));
    }
    return originals;
  }

  @Override
  public void writeTo(ConsumerRecord<byte[], byte[]> consumerRecord, PrintStream output) {
    if (printKey) {
      try {
        if (keyDeserializer != null) {
          Object deserializedKey = consumerRecord.key() == null
                                   ? null
                                   : keyDeserializer.deserialize(null, consumerRecord.key());
          output.write(
              deserializedKey != null ? deserializedKey.toString().getBytes(StandardCharsets.UTF_8)
                                      : NULL_BYTES);
        } else {
          writeTo(consumerRecord.key(), output);
        }
        output.write(keySeparator);
      } catch (IOException ioe) {
        throw new SerializationException("Error while formatting the key", ioe);
      }
    }
    try {
      writeTo(consumerRecord.value(), output);
      output.write(lineSeparator);
    } catch (IOException ioe) {
      throw new SerializationException("Error while formatting the value", ioe);
    }
  }

  private void writeTo(byte[] data, PrintStream output) throws IOException {
    Object object = deserialize(data);
    Schema schema = getSchema(object);

    try {
      JsonEncoder encoder = encoderFactory.jsonEncoder(schema, output);
      DatumWriter<Object> writer = new GenericDatumWriter<Object>(schema);
      if (object instanceof byte[]) {
        writer.write(ByteBuffer.wrap((byte[])object), encoder);
      } else {
        writer.write(object, encoder);
      }
      encoder.flush();
    } catch (AvroRuntimeException e) {
      throw new SerializationException(
          String.format("Error serializing Avro data of schema %s to json", schema), e);
    }
  }

  @Override
  public void close() {
    // nothing to do
  }
}
