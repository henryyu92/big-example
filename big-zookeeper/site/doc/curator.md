## Curator Framework
- framework
  ```java
  CuratorFramework client = CuratorFrameworkFactory
            .builder()
            .connectString("34.226.114.254:2181")
            .retryPolicy(new ExponentialBackoffRetry(100, 3))
            .connectionTimeoutMs(1000)
            .sessionTimeoutMs(1000)
            .build();
  
  // 创建 ZNode 节点  
  client.create().forPath(path, data);
  
  // 创建 ephemeral 节点
  client.create().withMode(CreateMode.EPHEMERAL).forPath(path, data); 
  
  // 创建带保护的 ephemeral_sequential 节点
  client.create().withProtection().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(path, data);
  
  // 修改节点数据
  Stat stat = client.setData().forPath(path, data);
  
  // 异步修改节点数据，修改完成异步通知 CuratorListener
  CuratorListener listener = new CuratorListener() {
	  @Override
	  public void eventReceived(CuratorFramework client, CuratorEvent event) throws Exception {

	  }
  };
  client.getCuratorListenable().addListener(listener);
  Stat stat = client.setData().inBackground().forPath(path, data);
  
  // 异步修改节点数据，修改完成异步通知 BackgroundCallback
  BackgroundCallback callback = new BackgroundCallback() {
      @Override
	  public void processResult(CuratorFramework client, CuratorEvent event) throws Exception {

      }
  };
  Stat stat = client.setData().inBackground(callback).forPath(path, data);
  
  // 删除节点
  client.delete().forPath(path);
  
  // 确保删除节点，当删除失败时会在后台继续删除直至删除成功
  client.delete().guaranteed().forPath(path);
  
  // 获取子节点并且设置 watch，watch 时间通过 BackgroundCallback 通知
  List<String> list = client.getChildren().watched().forPath(path);
  
  // 获取子节点并且设置 watch，watch 事件通过 watcher
  Watcher watcher = new Watcher() {
	  @Override
      public void process(WatchedEvent event) {
            
	  }
  };
  List<String> list = client.getChildren().usingWatcher(watcher).forPath(path);
  
  
  ```

  - 服务发现
  ```java
  ```
- 分布式锁
  ```java
  ```
- leader 选举
  ```java
  ```