package example.datamodel;

import java.util.function.BiFunction;

public class DataModelOp {

    public static void main(String[] args) {

        BiFunction<Integer, Integer, Integer> bf = (a, b) -> a + b;
        System.out.println(bf.apply(1, 2));

        System.out.println(bf.andThen(value -> value * value).apply(1, 2));

    }

    public static void get(){

    }

    public static void put(){

    }

    public static void delete(){

    }

}
