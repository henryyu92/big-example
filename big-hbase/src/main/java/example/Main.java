package example;

import org.apache.hadoop.hbase.util.HasThread;

/**
 * @author Administrator
 * @date 2020/4/4
 */
public class Main extends HasThread {

    private volatile boolean loop = false;

    public Main(){
        super("Main in test");
        loop = true;
    }

    @Override
    public void run() {
        System.out.println("thread in main");
        while (loop){
            System.out.println("looping...");
        }
    }

    public static void main(String[] args) throws InterruptedException {

        Main main = new Main();

        main.start();

        main.join();

        System.out.println("main");


    }
}
