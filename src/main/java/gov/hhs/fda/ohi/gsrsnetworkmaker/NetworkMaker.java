package gov.hhs.fda.ohi.gsrsnetworkmaker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import net.minidev.json.JSONArray;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static com.jayway.jsonpath.JsonPath.using;
import static gov.hhs.fda.ohi.gsrsnetworkmaker.Utils.*;

public class NetworkMaker {
    private static final Logger logger = Logger.getLogger(NetworkMaker.class);

    public static int DEFAULT_NESTING_LEVEL = 1;

    public static Map<Pattern, String> patternToLinkType = new HashMap() {{
        put(getLiteralPattern("$['mixture']['components'][\\d+]['substance']"), "Component");
        put(getLiteralPattern("$['mixture']['parentSubstance']"), "Mixture");
        put(getLiteralPattern("$['modifications']['agentModifications'][\\d+]['agentSubstance']"), "AgentModification");
        put(getLiteralPattern("$['modifications']['structuralModifications'][\\d+]['molecularFragment']"), "StructuralModification");
        put(getLiteralPattern("$['polymer']['classification']['parentSubstance']"), "PolymerClassification");
        put(getLiteralPattern("$['polymer']['monomers'][\\d+]['monomerSubstance']"), "Monomer");
        put(getLiteralPattern("$['relationships'][\\d+]['mediatorSubstance']"), "Relationship");
        put(getLiteralPattern("$['relationships'][\\d+]['relatedSubstance']"), "Relationship");
        put(getLiteralPattern("$['structurallyDiverse']['hybridSpeciesMaternalOrganism']"), "StructurallyDiverse");
        put(getLiteralPattern("$['structurallyDiverse']['hybridSpeciesPaternalOrganism']"), "StructurallyDiverse");
        put(getLiteralPattern("$['structurallyDiverse']['parentSubstance']"), "StructurallyDiverse");
    }};

    public static void main(String[] args) throws IOException {
        ImmutableTriple<File, File, Integer> parseArgs = parseArgs(args);
        File gsrsFile = parseArgs.left;
        File outputDirectory = parseArgs.middle;
        Integer nestingLevel = parseArgs.right;

        logger.debug("Getting nodes cache...");
        Map<String, String> nodesCache = getFullNodesCache(gsrsFile);
        showMemoryStats();

        generateNetworkFiles(gsrsFile, outputDirectory, nestingLevel, nodesCache);
    }

    private static ImmutableTriple<File, File, Integer> parseArgs(String[] args) {
        Options options = new Options();

        Option gsrsFileOpt = new Option("f", true, "The file with .gsrs extension.\nCheck out \"Public Data Releases\" section in https://gsrs.ncats.nih.gov/#/archive");
        options.addOption(gsrsFileOpt);

        options.addOption("d", true, "The output directory (created automatically) where json files will be written.\nDefault value: \"./jsons\"");
        options.addOption("l", true, "The level of nesting for nodes.\nDefault value: 1");
        options.addOption("h", false, "Show help");

        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(100);
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
            if (cmd.hasOption("h")) {
                printHelpAndExit(options, formatter, 0);
            }
            if (!cmd.hasOption("f")) {
                System.out.println("Missing required option: f");
                printHelpAndExit(options, formatter, 1);
            }
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            printHelpAndExit(options, formatter, 1);
        }

        String gsrsFilePath = cmd.getOptionValue("f");
        File gsrsFile = new File(gsrsFilePath);
        if (!gsrsFile.exists()) {
            logger.error("Specified gsrsFile " + gsrsFilePath + " does not exist.");
            System.exit(1);
        }

        String outputDirectoryPath = cmd.getOptionValue("d");
        File outputDirectory = new File(outputDirectoryPath);
        if (!isValidFile(outputDirectory)) {
            String currentDir = Paths.get("").toAbsolutePath().toString();
            outputDirectory = new File(currentDir, "jsons");
            logger.info("Fallback for outputDir to " + outputDirectory.getAbsolutePath());
        }
        createDirIfNotExists(outputDirectory);

        Integer nestingLevel = DEFAULT_NESTING_LEVEL;
        try {
            if (cmd.hasOption("l")) {
                nestingLevel = Integer.parseInt(cmd.getOptionValue("l"));
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid nesting level specified for \"l\" option: " + cmd.getOptionValue("l"));
            System.exit(1);
        }

        return ImmutableTriple.of(gsrsFile, outputDirectory, nestingLevel);
    }

    private static void printHelpAndExit(Options options, HelpFormatter formatter, int code) {
        formatter.printHelp(" ", options);
        System.exit(code);
    }

    public static Map<String, String> getFullNodesCache(File gsrsDumpFile) throws IOException {
        Map<String, String> nodesCache = new HashMap<>();
        try (InputStream fis = new FileInputStream(gsrsDumpFile)) {
            try (InputStream in = new GZIPInputStream(fis)) {
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().forEach(p -> {
                    String gsrsJson = p.trim();
                    nodesCache.put(getUuid(gsrsJson), gsrsJson);
                });
            }
        }
        return nodesCache;
    }

//    public static Map<String, Map> getPartialNodesCache(File gsrsDumpFile) throws IOException {
//        Map<String, Map> nodesCache = new HashMap();
//        try (InputStream fis = new FileInputStream(gsrsDumpFile)) {
//            try (InputStream in = new GZIPInputStream(fis)) {
//                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().forEach(p -> {
//                    String json = p.trim();
//                    nodesCache.put(getUuid(json), getNode(json));
//                });
//            }
//        }
//        return nodesCache;
//    }

    public static void generateNetworkFiles(File gsrsDumpFile, File outputDirectory, Integer nestingLevel, Map<String, String> nodesCache) throws IOException {
        try (InputStream fis = new FileInputStream(gsrsDumpFile)) {
            try (InputStream in = new GZIPInputStream(fis)) {
                File finalOutputDirectory = outputDirectory;
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().forEach(p -> {
                    String gsrsJson = p.trim();
                    String uuid = getUuid(gsrsJson);

                    logger.debug("----------Processing uuid " + uuid + "----------");
                    try {
                        String networkJson = getNetworkJson(gsrsJson, nodesCache, nestingLevel);
                        writeJsonFile(uuid, networkJson, finalOutputDirectory);
                    } catch (JsonProcessingException e) {
                        logger.error("Unable to get json string from result object for uuid " + uuid);
                    }
                });
            }
        }
    }

    public static String getNetworkJson(String json, Map<String, String> nodesCache, int nestingLevel) throws JsonProcessingException {
        List allNodes = new ArrayList();
        List allLinks = new ArrayList();

        Map rootNode = getNode(json);
        allNodes.add(rootNode);

        Set<String> addedNodeUuids = new HashSet();
        addedNodeUuids.add((String) rootNode.get("id"));

        List prevLevelNodes = allNodes;
        for (int i = 0; i < nestingLevel; i++) {
            int currentLevel = i + 1;
            logger.debug("Getting nodes and links for level " + currentLevel);
            ImmutablePair<List, List> nodesAndLinksForNewLevel = getNodesAndLinksForNodes(prevLevelNodes, nodesCache, addedNodeUuids);

            List newLevelNodes = nodesAndLinksForNewLevel.left;
            allNodes.addAll(newLevelNodes);
            prevLevelNodes = newLevelNodes;

            List newLevelLinks = nodesAndLinksForNewLevel.right;
            allLinks.addAll(newLevelLinks);
        }

        Map result = new HashMap();
        result.put("nodes", allNodes);
        result.put("links", allLinks);

        return new ObjectMapper().writeValueAsString(result);
    }

    public static ImmutablePair<List, List> getNodesAndLinksForNodes(List<Map<String, Object>> nodes, Map<String, String> nodesCache, Set<String> addedNodeUuids) {
        List<String> nodeUuids = nodes.stream().map(node -> (String) node.get("id")).collect(Collectors.toList());

        List newNodes = new ArrayList();
        List newLinks = new ArrayList();
        for (String nodeUuid : nodeUuids) {
            ImmutablePair<List, List> nodesAndLinks = getNodesAndLinks(nodeUuid, nodesCache, addedNodeUuids);
            newNodes.addAll(nodesAndLinks.left);
            newLinks.addAll(nodesAndLinks.right);
        }

        return ImmutablePair.of(newNodes, newLinks);
    }

    public static ImmutablePair<List, List> getNodesAndLinks(String sourceUuid, Map<String, String> nodesCache, Set<String> addedNodeUuids) {
        List nodes = new ArrayList();
        List<Map<String, Object>> links = new ArrayList<Map<String, Object>>();

        String sourceJson = nodesCache.get(sourceUuid);
        List<String> refuuidPaths = getRefuuidPaths(sourceJson);
        logger.debug("Found " + refuuidPaths.size() + " refuuids for uuid " + sourceUuid);

        for (String refuuidPath : refuuidPaths) {
            String targetUuid = getRefuuid(sourceJson, refuuidPath);
            String targetJson = nodesCache.get(targetUuid);
            if (targetJson == null) {
                logger.error("Unable to find json in cache for uuid " + targetUuid + " found by refuuidPath " + refuuidPath);
                continue;
            }

            boolean isSelfReference = sourceUuid.equals(targetUuid);
            boolean isAlreadyAdded = addedNodeUuids.contains(targetUuid);
            if (isAlreadyAdded) {
//                logger.warn("Node with targetUuid: " + targetUuid + " is already added");
            } else if (isSelfReference) {
                logger.warn("Found self-reference for uuid: " + sourceUuid + ", refuuidPath: " + refuuidPath);
            } else {
                nodes.add(getNode(targetJson));
                addedNodeUuids.add(targetUuid);
            }

            // self-reference link is valid and should be added among with normal links
            links.add(getLink(sourceJson, sourceUuid, targetUuid, refuuidPath));
        }

        return ImmutablePair.of(nodes, links);
    }

    public static List<String> getRefuuidPaths(String json) {
        Configuration conf = Configuration.builder().options(com.jayway.jsonpath.Option.AS_PATH_LIST).build();
        try {
            // Gets all object paths where refuuid is present
            return using(conf).parse(json).read("$..[?(@.refuuid)]");
        } catch (PathNotFoundException e) {
            // no refuuid paths found
            return Collections.emptyList();
        }
    }

    public static String getRefuuid(String json, String refuuidPath) {
        return JsonPath.read(json, refuuidPath + ".refuuid");
    }

    public static String getUuid(String json) {
        return JsonPath.read(json, "$.uuid");
    }

    public static Map<String, Object> getNode(String json) {
        Map<String, Object> nodeJson = new HashMap<>();

        String uuid = getUuid(json);
        nodeJson.put("id", uuid);

        List<String> names = JsonPath.read(json, "$.names[?(@.preferred == true || @.displayName == true)]..name");
        String name = names.size() > 0 ? names.get(0) : uuid;
        nodeJson.put("n", name);
        nodeJson.put("tags", Collections.emptyList());

        String substanceClass = JsonPath.read(json, "$.substanceClass");
        nodeJson.put("nodeType", substanceClass);

        Map<String, Object> nodeObj = new HashMap<>();
        nodeObj.put("Substance Class", substanceClass);
        nodeObj.put("Type", substanceClass);
        nodeObj.put("Status", JsonPath.read(json, "$.status"));

        String delimiter = "<br>";
        nodeObj.put("Other Names", String.join(delimiter, (Iterable<? extends CharSequence>) JsonPath.read(json, "$.names..name")));

        JSONArray codes = JsonPath.read(json, "$.codes");
        StringBuilder codesSb = new StringBuilder();
        for (Object codeObject : codes) {
            Map map = (Map) codeObject;
            String codeSystem = (String) map.get("codeSystem");
            String code = (String) map.get("code");
            if (!StringUtils.isEmpty(code) && !StringUtils.isEmpty(codeSystem)) {
                codesSb.append(codeSystem).append(": ").append(code).append(delimiter);
            }
        }
        if (codesSb.length() > 0) {
            codesSb.setLength(codesSb.length() - delimiter.length());
            nodeObj.put("Codes", codesSb.toString());
        }
        nodeJson.put("obj", nodeObj);

        return nodeJson;
    }

    public static Map<String, Object> getLink(String sourceJson, String sourceUuid, String targetUuid, String refuuidPath) {
        Map<String, Object> link = new HashMap<>();
        link.put("source", sourceUuid);
        link.put("target", targetUuid);
        link.put("tags", Collections.emptyList());

        String linkType = getLinkType(refuuidPath);
        link.put("linkType", linkType);

        // Parent obj is retrieved differently for paths with array and without array:
        // With array: "$['modifications']['structuralModifications'][0]['molecularFragment']" => "$['modifications']['structuralModifications'][0]
        // Without array: "$['mixture']['parentSubstance']" => "$['mixture']['parentSubstance']"
        Pattern withArrayPattern = Pattern.compile("\\[\\d+\\]\\['\\w+'\\]$");
        boolean hasArrayInPath = withArrayPattern.matcher(refuuidPath).find();
        String parentPath = hasArrayInPath ? refuuidPath.substring(0, refuuidPath.lastIndexOf("[")) : refuuidPath;
        Map parentObject = JsonPath.read(sourceJson, parentPath);

        String type = (String) parentObject.get("type");
        String name = (String) parentObject.get("name");
        String n = name != null ? name : type;
        putIfNotNull(link, "n", n);

        Map<String, String> linkObj = new HashMap<String, String>();
        linkObj.put("Link Type", linkType);
        putIfNotNull(linkObj, "type", type);
        putIfNotNull(linkObj, "uuid", parentObject.get("uuid"));
        link.put("obj", linkObj);

        return link;
    }

    public static String getLinkType(String refuuidPath) {
        for (Map.Entry<Pattern, String> entry : patternToLinkType.entrySet()) {
            Pattern p = entry.getKey();
            if (p.matcher(refuuidPath).matches()) {
                return entry.getValue();
            }
        }

        // Fallback for new (undocumented) link types. For example should get "Transformation" from "$['transformations']['a'][0]['b']"
        String linkType = refuuidPath.substring(refuuidPath.indexOf("$['") + 3, refuuidPath.indexOf("']"));
        linkType = capitalizeFirstLetter(linkType);
        return linkType.endsWith("s") ? linkType.substring(0, linkType.length() - 1) : linkType;
    }
}
