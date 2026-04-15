package com.crypto.trader.util;

/**
 * OKX 交易所符号/周期转换工具类。
 *
 * <p>统一 OKX REST 和 WebSocket 客户端的符号与 K 线周期格式转换逻辑。</p>
 */
public final class OkxSymbolUtils {

    private OkxSymbolUtils() {}

    /** BTCUSDT -> BTC-USDT */
    public static String toOkxInstId(String symbol) {
        if (symbol == null || symbol.length() < 4) return symbol;
        String base  = symbol.substring(0, symbol.length() - 4);
        String quote = symbol.substring(symbol.length() - 4);
        return base + "-" + quote;
    }

    /** BTC-USDT -> BTCUSDT */
    public static String fromOkxInstId(String instId) {
        if (instId == null) return null;
        return instId.replace("-", "");
    }

    /** BTCUSDT -> BTC-USDT-SWAP */
    public static String toOkxSwapInstId(String symbol) {
        return toOkxInstId(symbol) + "-SWAP";
    }

    /** 内部周期 -> OKX bar 参数 */
    public static String toOkxBar(String interval) {
        if (interval == null) return "1m";
        return switch (interval) {
            case "1m", "3m", "5m", "15m", "30m" -> interval;
            case "1h"  -> "1H";
            case "2h"  -> "2H";
            case "4h"  -> "4H";
            case "6h"  -> "6H";
            case "12h" -> "12H";
            case "1d"  -> "1D";
            case "1w"  -> "1W";
            case "1M"  -> "1M";
            default    -> "1m";
        };
    }

    /** candle1m -> 1m, candle1H -> 1h, candle1D -> 1d */
    public static String parseIntervalFromChannel(String channel) {
        if (channel == null || !channel.startsWith("candle")) return "1m";
        String raw = channel.substring(6);
        return raw.toLowerCase();
    }
}
