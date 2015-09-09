package cz.muni.fgdovin.bachelorthesis.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.muni.fgdovin.bachelorthesis.support.ExchangeType;
import cz.muni.fgdovin.bachelorthesis.support.JSONFlattener;
import cz.muni.fgdovin.bachelorthesis.support.RabbitManagementApiExtension;
import org.nigajuan.rabbit.management.client.RabbitManagementApi;
import org.nigajuan.rabbit.management.client.domain.aliveness.Status;
import org.nigajuan.rabbit.management.client.domain.binding.Binding;
import org.nigajuan.rabbit.management.client.domain.binding.queue.QueueBind;
import org.nigajuan.rabbit.management.client.domain.exchange.Exchange;
import org.nigajuan.rabbit.management.client.domain.queue.Arguments;
import org.nigajuan.rabbit.management.client.domain.queue.Queue;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import retrofit.client.Response;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@inheritDoc}
 *
 * @author Filip Gdovin
 * @version 20. 4. 2015
 */
@Service
public class RabbitMqServiceImpl implements RabbitMqService {

    @Resource
    private Environment environment; //to load RabbitMQ server info from properties
    private RabbitManagementApi rabbitManagementApi;
    private RabbitManagementApiExtension rabbitMgmtApiExtension;
    private JSONFlattener jsonFlattener;

    private String exchangeName;
    private String outputExchangeName;
    private String exchangePrefix;
    private String vhost;

    /**
     * Method called right after instance of this class is created.
     * Loads necessary values from config file and creates
     * instance of RabbitManagementApi for accessing
     * RabbitMQ REST interface.
     */
    @PostConstruct
    public void setUp() {
        String username = this.environment.getProperty("serverUsername");
        String password = this.environment.getProperty("serverPassword");
        String serverHost = this.environment.getProperty("serverHost");
        String serverPort = this.environment.getProperty("serverPort");
        this.exchangeName = this.environment.getProperty("exchangeName");
        this.outputExchangeName = this.environment.getProperty("outputExchangeName");
        this.exchangePrefix = this.environment.getProperty("exchangePrefix");
        this.vhost = this.environment.getProperty("vhost");

        String host = "http://" + serverHost + ":" + serverPort;
        this.rabbitManagementApi = RabbitManagementApi.newInstance(host, username, password);
        this.rabbitMgmtApiExtension = RabbitManagementApiExtension.newInstance(host, username, password);
        this.jsonFlattener = new JSONFlattener(new ObjectMapper());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String isAlive() {
        Status serverStatus;
        try {
            serverStatus = this.rabbitManagementApi.alivenessTest(this.vhost);  //takes vhost name as argument
        } catch (Exception ex) {
            return "NOK" + ex.getMessage();
        }
        return serverStatus.getStatus();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean createQueue(String queueName) {
        return createQueue(queueName, this.outputExchangeName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean createQueue(String queueName, String exchangeName) {
        return createQueue(queueName, exchangeName, queueName);
    }
    
    public boolean createQueue(String queueName, String exchangeName, String routingKey) {
        //create new queue
        Queue newOne = new Queue();
        newOne.setAutoDelete(false);
        newOne.setDurable(false);
        newOne.setArguments(new Arguments());
        Response response = this.rabbitManagementApi.createQueue(this.vhost, queueName, newOne);
        if((response.getStatus() > 199) && (response.getStatus() < 300)) {
            //bind this queue to specific exchange
            QueueBind bind = new QueueBind();
            bind.setRoutingKey(routingKey);
            response = this.rabbitManagementApi.bindExchangeToQueue(this.vhost, exchangeName, queueName, bind);
        }
        return ((response.getStatus() > 199) && (response.getStatus() < 300));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteQueue(String queueName) {
        this.rabbitManagementApi.deleteQueue(this.vhost, queueName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteAllQueues() {
        List<String> allQueues = this.listQueues();
        allQueues.forEach(this::deleteQueue);
    }

    public List<String> getQueueNames() {
        List<Queue> allQueues = this.rabbitManagementApi.listQueues();
        return allQueues.stream().map(Queue::getName).collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> listQueues() {
        return listQueues(this.exchangeName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> listQueues(String exchangeName) {
        List<String> result = new ArrayList<>();
        List<String> allQueueNames = this.getQueueNames();
        List<Binding> allBindings = this.rabbitManagementApi.listBindings(this.vhost);
        result.addAll(allBindings.stream().filter(oneBind -> oneBind.getSource().equals(exchangeName))
                .filter(oneBind -> allQueueNames.contains(oneBind.getDestination()))
                .map(Binding::getDestination).collect(Collectors.toList()));
        return result;
    }
    
    @Override
    public boolean createExchange(String exchangeName) {
        return createExchange(exchangeName, ExchangeType.fanout, exchangeName);
    }

    @Override
    //bound by default to the main esperExchange; TODO enable arbitrary binding?
    public boolean createExchange(String exchangeName, ExchangeType type, String routingKey) {
        Exchange exchange = new Exchange();
        exchange.setDurable(true);
        String name = exchangeName;
        if (! name.startsWith(this.environment.getProperty("exchangePrefix"))) {
            name = this.environment.getProperty("exchangePrefix") + "_" + exchangeName;
        }
        exchange.setName(name);
        exchange.setType(type.name());
        exchange.setVhost(this.vhost);
        
        Response response = this.rabbitManagementApi.createExchange(this.vhost, name, exchange);
        if ((response.getStatus() > 199) && (response.getStatus() < 300)) {
            //bind this exchange to the root esper exchange
            QueueBind bind = new QueueBind(); //TODO schema!
            bind.setRoutingKey(routingKey);
            response = this.rabbitMgmtApiExtension.bindExchangeToExchangeWithKey(this.vhost, this.exchangeName, name, bind);
        }
        return ((response.getStatus() > 199) && (response.getStatus() < 300));
    }

    @Override
    public void deleteExchange(String exchangeName) {
        this.rabbitManagementApi.deleteExchange(this.vhost, exchangeName);
    }

    @Override
    public void deleteAllExchanges() {
        List<String> allExchanges = this.listExchangeNames();
        allExchanges.forEach(this::deleteExchange);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Exchange> listExchanges() {
        List<Exchange> esperExchanges = new ArrayList<>();
        List<Exchange> allExchanges = this.rabbitManagementApi.listExchanges();
        esperExchanges.addAll(allExchanges.stream().filter(oneExchange -> oneExchange.getName().startsWith(this.exchangePrefix)).collect(Collectors.toList()));
        return esperExchanges;
    }

    @Override
    public List<String> listExchangeNames() {
        List<String> esperExchangeNames = new ArrayList<>();
        List<Exchange> allExchanges = this.listExchanges();
        esperExchangeNames.addAll(allExchanges.stream()
                .filter(oneExchange -> oneExchange.getName()
                        .startsWith(this.exchangePrefix))
                .map(Exchange::getName).collect(Collectors.toList()));
        return esperExchangeNames;
    }

    private Exchange findExchange(String exchangeName) {
        List<Exchange> allExchanges = this.rabbitManagementApi.listExchanges();
        for(Exchange oneExchange : allExchanges) {
            if(oneExchange.getName().equals(exchangeName)) {
                return oneExchange;
            }
        }
        return null;
    }

    @Override
    public Map<String, Object> getSchemaForExchange(String exchangeName) {
        Exchange esperExchange = this.findExchange(exchangeName);
        Map<String, Object> temp = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        try {
            temp = this.jsonFlattener.jsonToFlatMap(
                    String.valueOf(esperExchange.getArguments().getAdditionalProperties().get("schema")));
        } catch (IOException e) {
            e.printStackTrace();
        }

        String timestampKey = environment.getProperty("timestampAttribute");

        for(String key : temp.keySet()) {
            if(key.equals(timestampKey)) {
                result.put("timestamp", Long.class.getSimpleName());
                continue;
            }
            result.put(key, temp.get(key).getClass().getSimpleName());
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getExchangeName() {
        return this.exchangeName;
    }
}
