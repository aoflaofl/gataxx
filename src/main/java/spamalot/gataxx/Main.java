package spamalot.gataxx;

/** Entry point for the gataxx engine. Speaks UAI (Universal Ataxx Interface) on stdin/stdout. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.out.println("gataxx " + version());
    }

    static String version() {
        return "0.1.0";
    }
}
