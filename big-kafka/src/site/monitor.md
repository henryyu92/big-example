### 监控数据
Kafka 自身提供的监控指标都可以通过 JMX 来获取，在 Kafka 启动前配置 JMX_OPT 设置 JMX 端口号开启 JMX 功能：
```shell
# 在 kafka-run-class.sh 中增加 JMX_PORT 端口
export JXM_PORT = 9099
```
开通 JMX 之后会在 ZooKeeper 的 /brokers/ids/<brokerId> 节点中有对应的 jmx_port 字段。
```java
public class JmxConnection {
    private MBeanServerConnection connection;
    private String jmxURL;
    private String ipAndPort;

    public JmxConnection(String ipAndPort) {
        this.ipAndPort = ipAndPort;
    }

    public boolean init() {
        jmxURL = "service:jmx:rmi:///jndi/rmi://" + ipAndPort + "jmxrmi";
        try {
            JMXServiceURL serviceURL = new JMXServiceURL(jmxURL);
            JMXConnector connector = JMXConnectorFactory.connect(serviceURL, null);
            connection = connector.getMBeanServerConnection();
            if (connection == null) {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    public double getMsgInPerSec() {
        String objectName = "kafka.server.type=BrokerTopicMetrics,name=MessagesInPerSec";
        Object val = getAttribute(objectName, "OneMinuteRate");
        if (val != null) {
            return (double) (Double) val;
        }
        return 0.0;
    }

    public Object getAttribute(String objName, String objAttr){
        ObjectName objectName;
        try{
            objectName = new ObjectName(objName);
            return connection.getAttribute(objectName, objAttr);
        }catch (Exception e){
            e.printStackTrace();
        }
        return null;
    }

    public static void main(String[] args) {
        JmxConnection connection = new JmxConnection("localhost:9092");
        connection.init();
        System.out.println(connection.getMsgInPerSec());
    }
}
```

### 启动监控

