package com.blade.kit;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * date kit
 *
 * @author biezhi
 *         2017/6/2
 */
public final class DateKit {

    private DateKit() {
        throw new IllegalStateException("DateKit shouldn't be constructed!");
    }

    /**
     * return current unix time
     *
     * @return
     */
    public static long nowUnix() {
        return Instant.now().getEpochSecond();
    }

    /**
     * format unix time to string
     *
     * @param unixTime
     * @param pattern
     * @return
     */
    public static String toUnix(long unixTime, String pattern) {
        return Instant.ofEpochSecond(unixTime).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * format date to string
     *
     * @param date
     * @param pattern
     * @return
     */
    public static String toString(Date date, String pattern) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * format string time to unix time
     *
     * @param time
     * @param partten
     * @return
     */
    public static int toUnix(String time, String partten) {
        LocalDateTime formatted = LocalDateTime.parse(time, DateTimeFormatter.ofPattern(partten));
        return (int) formatted.atZone(ZoneId.systemDefault()).toInstant().getEpochSecond();

    }

    /**
     * format string (yyyy-MM-dd HH:mm:ss) to unix time
     *
     * @param time
     * @return
     */
    public static int toUnix(String time) {
        return toUnix(time, "yyyy-MM-dd HH:mm:ss");
    }

}