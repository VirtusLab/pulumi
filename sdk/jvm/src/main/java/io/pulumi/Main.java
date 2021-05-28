package io.pulumi;

import java.lang.Thread;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("args: " + args);
        System.out.println("env: " + System.getenv());
        Thread.sleep(1000);
    }
}