package gov.hhs.fda.ohi.gsrsnetworkmaker;

import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

public class Utils {
    private static final Logger logger = Logger.getLogger(Utils.class);

    public static boolean isValidFile(File f) {
        try {
            f.getCanonicalPath();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void createDirIfNotExists(File f) {
        if (!f.exists()) {
            f.mkdirs();
        }
    }

    public static String capitalizeFirstLetter(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public static boolean writeJsonFile(String uuid, String jsonString, File dir) {
        File jsonFile = new File(dir, uuid + ".json");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(jsonFile))) {
            writer.write(jsonString);
            return true;
        } catch (IOException e) {
            logger.error("Error occurred while writing json file with uuid " + uuid);
            e.printStackTrace();
            return false;
        }
    }

    public static boolean putIfNotNull(Map map, Object key, Object value) {
        if (value != null) {
            map.put(key, value);
            return true;
        }
        return false;
    }

    public static Pattern getLiteralPattern(String regex) {
        return Pattern.compile(regex, Pattern.LITERAL);
    }

    public static void showMemoryStats() {
        int mb = 1024 * 1024;
        Runtime rn = Runtime.getRuntime();
        String[] messageParts = new String[]{
                "***** Heap utilization statistics [MB] *****",
                "Total Memory: " + rn.totalMemory() / mb,
                "Free Memory: " + rn.freeMemory() / mb,
                "Used Memory: " + (rn.totalMemory() - rn.freeMemory()) / mb,
                "Max Memory: " + rn.maxMemory() / mb
        };
        logger.debug(String.join(System.lineSeparator(), messageParts));
    }
}
