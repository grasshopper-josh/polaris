package todo.list.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import protocol.todo.List;
import protocol.todo.ListStatus;
import protocol.todo.TodoCommand;
import protocol.todo.TodoUpdate;
import todo.ws.endpoint.schema.WsUpdate;

import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class TodoListService {

    public static Properties defaultProperties = new Properties();
    public static String kafka_bootstrap_servers;
    public static String schema_registry_url;

    public static String todo_commands_topic;
    public static String todo_list_updates_topic;
    public static String todo_updates_topic;
    public static String todo_lists_table;

    public static KafkaStreams streams;

    public static void configureKafka() {
        String kafka_application_id = "todo-list-service";

        // All environmental configuration passed in from environment variables
        //
        kafka_bootstrap_servers = System.getenv("kafka_bootstrap_servers");
        schema_registry_url = System.getenv("schema_registry_url");
        System.out.println("kafka_bootstrap_servers: " + kafka_bootstrap_servers);
        System.out.println("schema_registry_url: " + schema_registry_url);

        // Streams config
        //
        defaultProperties.put(StreamsConfig.APPLICATION_ID_CONFIG, kafka_application_id);
        defaultProperties.put(StreamsConfig.CLIENT_ID_CONFIG, kafka_application_id + "-client");
        defaultProperties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafka_bootstrap_servers);

        // How should we handle deserialization errors?
        //
        defaultProperties.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndFailExceptionHandler.class);

        // This is for producers really only
        //
        defaultProperties.put("key.serializer", "io.confluent.kafka.serializers.KafkaAvroSerializer");
        defaultProperties.put("value.serializer", "io.confluent.kafka.serializers.KafkaAvroSerializer");
        defaultProperties.put("schema.registry.url", schema_registry_url);

        // Example specific configuration
        //
        todo_commands_topic = System.getenv("todo_commands_topic");
        System.out.println("todo_commands_topic: " + todo_commands_topic);
        todo_list_updates_topic = System.getenv("todo_list_updates_topic");
        System.out.println("todo_list_updates_topic: " + todo_list_updates_topic);
        todo_lists_table = System.getenv("todo_lists_table");
        System.out.println("todo_lists_table: " + todo_lists_table);
        todo_updates_topic = System.getenv("todo_updates_topic");
        System.out.println("todo_updates_topic: " + todo_updates_topic);
    }

    public static void startKafkaStreams() {
        // Avro serde configs
        //
        final Map<String, String> serdeConfig =
                Collections.singletonMap(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
                        schema_registry_url);

        // Process the stream
        //
        final StreamsBuilder builder = new StreamsBuilder();

        // Launch the single update service
        //
        startCommandsService(builder, serdeConfig);

        // Boot up the streams
        //
        streams = new KafkaStreams(builder.build(), defaultProperties);
        System.out.println("Starting streams...");
        streams.start();
    }

    public static void startCommandsService(StreamsBuilder builder, Map<String, String> serdeConfig) {

        // Strings
        //
        Serde<String> stringSerde = Serdes.serdeFrom(String.class);

        // For general commands
        //
        final SpecificAvroSerde<TodoCommand> todoCommandSerde = new SpecificAvroSerde<>();
        todoCommandSerde.configure(serdeConfig, false);

        // For general updates
        //
        final SpecificAvroSerde<TodoUpdate> todoUpdateSerde = new SpecificAvroSerde<>();
        todoUpdateSerde.configure(serdeConfig, false);

        // For list updates
        //
        final SpecificAvroSerde<List> listSerde = new SpecificAvroSerde<>();
        listSerde.configure(serdeConfig, false);

        final KStream<String, TodoCommand> commands =
                builder.stream(todo_commands_topic, Consumed.with(stringSerde, todoCommandSerde));

        final KStream<String, List> listUpdates =
            commands
                // Only interested in LIST commands
                //
                .filter((String session, TodoCommand command) ->
                    command.getType().equals("LIST"))
                // Ignore refresh commands for updates
                //
                .filter((String session, TodoCommand command) ->
                    !command.getCmd().equals("REFRESH"))
                // Map these to actual LIST commands (ie parse the data portion)
                //
                .flatMap((String session, TodoCommand command) -> {
                    // Use a flatmap because there might not be an actual update... could
                    // be badly formatted or something
                    //
                    LinkedList result = new LinkedList<KeyValue<String, List>>();

                    // Otherwise handle the create or delete
                    //
                    JsonParser dataParser = new JsonParser();
                    JsonObject data = dataParser.parse(command.getData()).getAsJsonObject();

                    // Need the list name - but could be null if naughty data
                    //
                    if (data.get("name") == null) {
                        return result;
                    }

                    String listName = data.get("name").getAsString();
                    List listUpdate = new List();

                    switch (command.getCmd()) {
                        case "CREATE": {
                            listUpdate.setName(listName);
                            listUpdate.setStatus(ListStatus.ACTIVE);
                            break;
                        }
                        case "DELETE": {
                            listUpdate.setName(listName);
                            listUpdate.setStatus(ListStatus.DELETED);
                            break;
                        }
                    }

                    System.out.println("Put :List on " + todo_list_updates_topic);

                    result.add(KeyValue.pair(session, listUpdate));
                    return result;
                })
                // Pump to list update topic
                //
                .through(todo_list_updates_topic, Produced.with(stringSerde, listSerde))
                ;

        final KStream<String, TodoUpdate> updates =
            listUpdates
                // Then also deliver as a system level update to the todo-updates
                //
                .map((String session, List listUpdate) -> {
                    TodoUpdate update = new TodoUpdate();

                    update.setType("LIST");
                    update.setAction(listUpdate.getStatus().toString());

                    Gson gson = new Gson();
                    update.setData(gson.toJson(listUpdate));

                    System.out.println("Put :List on list-internal-updates");

                    return KeyValue.pair(session, update);
                })
                .through("list-internal-updates", Produced.with(stringSerde, todoUpdateSerde))
                ;

        // Lists - ktable
        //
        final KTable<String, List> listTable =
            listUpdates
                .map((String k, List list) -> {
                    System.out.println("Tabling :List " + list.getName() + " " + list.getStatus().toString());
                    return KeyValue.pair(list.getName(), list);
                })
                .groupByKey(Serialized.with(stringSerde, listSerde))
                .reduce((v1, v2) -> v2, Materialized.as(todo_lists_table));

        final KStream<String, TodoUpdate> refreshes =
            commands
                // Only interested in LIST commands
                //
                .filter((String session, TodoCommand command) ->
                    command.getType().equals("LIST") && command.getCmd().equals("REFRESH"))
                .flatMap((String session, TodoCommand command) -> {
                    ReadOnlyKeyValueStore<String, List> store = streams.store(todo_lists_table, QueryableStoreTypes.keyValueStore());

                    KeyValueIterator<String, List> values = store.all();

                    LinkedList refresh = new LinkedList<KeyValue<String, TodoUpdate>>();

                    while (values.hasNext()) {
                        KeyValue<String, List> kv = values.next();

                        // Don't send update of deletes entries
                        //
                        if (kv.value.getStatus() != ListStatus.ACTIVE)
                            continue;

                        TodoUpdate update = new TodoUpdate();

                        update.setType("LIST");
                        update.setAction(kv.value.getStatus().toString());

                        Gson gson = new Gson();
                        update.setData(gson.toJson(kv.value));

                        refresh.add(KeyValue.pair(session, update));
                    }
                    System.out.println("Approximately " + store.approximateNumEntries() + " total lists, and " + refresh.size() + " in update");
                    return refresh;
                })
                .through("list-internal-refreshes", Produced.with(stringSerde, todoUpdateSerde))
                ;

        refreshes
            .merge(updates)
            .through(todo_updates_topic, Produced.with(stringSerde, todoUpdateSerde))
            ;

    }

    public static void main(String[] args) throws Exception {
        // Configure Kafka
        //
        configureKafka();

        // Create the required topics
        //
        System.out.println("Creating topics that might not exist");
        AdminClient admin = AdminClient.create(defaultProperties);
        CreateTopicsResult result = admin.createTopics(Arrays.asList(
                new NewTopic(todo_commands_topic, 12, (short)1),
                new NewTopic(todo_updates_topic, 12, (short)1),
                new NewTopic(todo_list_updates_topic, 12, (short)1)
        ));

        try {
            result.all().get(60, TimeUnit.SECONDS);
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                System.out.println(e.getMessage());
            }
            else {
                throw e;
            }
        }

        startKafkaStreams();
    }

}
