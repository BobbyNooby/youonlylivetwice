package dev.bobbynooby.youOnlyLiveTwice.utils;

public class PluginPrint {

    private static final String preMessage = "[YouOnlyLiveTwice] ";

    public static void println(Object message) {
        System.out.println(preMessage + message.toString());
    }

    public static void print(Object message) {
        System.out.print(preMessage + message.toString());
    }

}
