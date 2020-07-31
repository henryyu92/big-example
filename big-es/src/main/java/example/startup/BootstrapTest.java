package example.startup;

import java.util.concurrent.CountDownLatch;

public class BootstrapTest {

    private static final CountDownLatch latch = new CountDownLatch(1);
    private final Thread keepAlive;

    public BootstrapTest(){
        keepAlive = new Thread(()->{
            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        keepAlive.setDaemon(false);
        addShutdownHook(latch::countDown);
    }

    public void start(){
        keepAlive.start();
    }

    public void addShutdownHook(Runnable shutdown){
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown));
    }

    public static void main(String[] args) {
        BootstrapTest bootStrapTest = new BootstrapTest();
        bootStrapTest.start();
        System.out.println("shutting down");
    }
}
