package com.redhat.garden;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import com.redhat.garden.events.DryConditionsDetected;
import com.redhat.garden.events.LowNutrientsDetected;
import com.redhat.garden.events.LowTemperatureDetected;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;
import io.quarkus.kafka.client.serialization.ObjectMapperSerde;

@ApplicationScoped
public class GardenStreamsTopologyBuilder {

    private static final double LOW_TEMPERATURE_THRESHOLD_CELSIUS = 5.0;
    private static final double LOW_HUMIDITY_THRESHOLD_PERCENT = 0.2;
    private static final double LOW_NUTRIENTS_THRESHOLD_PERCENT = 0.5;
    public static String SENSOR_MEASUREMENTS_TOPIC = "sensor-measurements";
    public static String LOW_TEMPERATURE_EVENTS_TOPIC = "low-temperature-events";
    public static String DRY_CONDITIONS_EVENTS_TOPIC = "dry-conditions-events";
    public static String LOW_NUTRIENTS_EVENTS_TOPIC = "low-nutrients-events";

    ObjectMapperSerde<SensorMeasurement> sensorMeasurementSerde = new ObjectMapperSerde<>(SensorMeasurement.class);
    ObjectMapperSerde<LowTemperatureDetected> lowTemperatureEventSerde = new ObjectMapperSerde<>(LowTemperatureDetected.class);
    ObjectMapperSerde<DryConditionsDetected> dryConditionsEventSerde = new ObjectMapperSerde<>(DryConditionsDetected.class);
    ObjectMapperSerde<LowNutrientsDetected> lowNutrientsEventSerde = new ObjectMapperSerde<>(LowNutrientsDetected.class);

    @Produces
    public Topology build() {
        StreamsBuilder builder = new StreamsBuilder();

        builder
            .stream(SENSOR_MEASUREMENTS_TOPIC, Consumed.with(Serdes.Integer(), sensorMeasurementSerde))
            .split()
                .branch((sensorId, measurement) -> measurement.property.equals("temperature"), Branched.withConsumer(this::proccessTemperature))
                .branch((sensorId, measurement) -> measurement.property.equals("humidity"), Branched.withConsumer(this::processHumidity))
                .branch((sensorId, measurement) -> measurement.property.equals("nutrients"), Branched.withConsumer(this::processNutrients));

        return builder.build();
    }

    private void proccessTemperature(KStream<Integer,SensorMeasurement> temperatureMeasurements) {
        temperatureMeasurements
            .filter((sensorId, measurement) -> measurement.value < LOW_TEMPERATURE_THRESHOLD_CELSIUS)
            .mapValues((measurement) -> new LowTemperatureDetected("garden", measurement.sensorId, measurement.value, measurement.timestamp))
            .to(LOW_TEMPERATURE_EVENTS_TOPIC, Produced.with(Serdes.Integer(), lowTemperatureEventSerde));
    }

    private void processHumidity(KStream<Integer,SensorMeasurement> humidityMeasurements) {
        humidityMeasurements
            .filter((sensorId, measurement) -> measurement.value < LOW_HUMIDITY_THRESHOLD_PERCENT)
            .mapValues((measurement) -> new DryConditionsDetected("garden", measurement.sensorId, measurement.value, measurement.timestamp))
            .to(DRY_CONDITIONS_EVENTS_TOPIC, Produced.with(Serdes.Integer(), dryConditionsEventSerde));
    }

    private void processNutrients(KStream<Integer,SensorMeasurement> nutrientsMeasurements) {
        nutrientsMeasurements
            .filter((sensorId, measurement) -> measurement.value < LOW_NUTRIENTS_THRESHOLD_PERCENT)
            .mapValues((measurement) -> new LowNutrientsDetected("garden", measurement.sensorId, measurement.value, measurement.timestamp))
            .to(LOW_NUTRIENTS_EVENTS_TOPIC, Produced.with(Serdes.Integer(), lowNutrientsEventSerde));
    }

}