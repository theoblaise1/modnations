package io.github.theoblaise1.sovereignfrontier.util;

/** Formatage de la monnaie du mod (le crédit, abrégé « cr »). */
public final class Money {
    private Money() {
    }

    public static String format(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder sb = new StringBuilder();
        int len = digits.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return (amount < 0 ? "-" : "") + sb + " cr";
    }
}
