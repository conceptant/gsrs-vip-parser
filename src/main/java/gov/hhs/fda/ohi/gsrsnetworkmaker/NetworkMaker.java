package gov.hhs.fda.ohi.gsrsnetworkmaker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.PathNotFoundException;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static com.jayway.jsonpath.JsonPath.using;
import static gov.hhs.fda.ohi.gsrsnetworkmaker.Utils.*;

public class NetworkMaker {
    private static final Logger logger = Logger.getLogger(NetworkMaker.class);

    public static String DEFAULT_OUTPUT_DIRECTORY = "jsons";
    public static int DEFAULT_NESTING_LEVEL = 4;
    public static int DEFAULT_MAX_NUMBER_OF_ELEMENTS = 1000;
    public static int DEFAULT_MAX_NUMBER_OF_LINKS_PER_NODE = 50;

    public static String TAG_FETCHED = "fetched";
    public static String TAG_UNFETCHED = "unfetched";


    public static Map<Pattern, String> patternToLinkType = new LinkedHashMap() {{
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
        Args parsedArgs = parseArgs(args);

        logger.debug("Getting nodes cache...");
        Map<String, String> nodesCache = getFullNodesCache(parsedArgs.gsrsFile);
        showMemoryStats();

        generateNetworkFiles(parsedArgs, nodesCache);
    }

    private static Args parseArgs(String[] args) {
        Options options = new Options();

        Option gsrsFileOpt = new Option("f", true, "The file with .gsrs extension.\nCheck out \"Public Data Releases\" section in https://gsrs.ncats.nih.gov/#/archive");
        options.addOption(gsrsFileOpt);

        options.addOption("d", true, "The output directory (created automatically) where json files will be written.\nDefault value: " + DEFAULT_OUTPUT_DIRECTORY);
        options.addOption("l", true, "The level of nesting for nodes.\nDefault value: " + DEFAULT_NESTING_LEVEL);
        options.addOption("m", true, "The maximum number of elements (nodes+links) for the whole network file.\nDefault value: " + DEFAULT_MAX_NUMBER_OF_ELEMENTS);
        options.addOption("n", true, "The maximum number of links for each node.\nDefault value: " + DEFAULT_MAX_NUMBER_OF_LINKS_PER_NODE);
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
            outputDirectory = new File(currentDir, DEFAULT_OUTPUT_DIRECTORY);
            logger.info("Fallback for outputDir to " + outputDirectory.getAbsolutePath());
        }
        createDirIfNotExists(outputDirectory);

        int nestingLevel = DEFAULT_NESTING_LEVEL;
        try {
            if (cmd.hasOption("l")) {
                nestingLevel = Integer.parseInt(cmd.getOptionValue("l"));
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid nesting level specified for \"l\" option: " + cmd.getOptionValue("l"));
            System.exit(1);
        }

        int maxNumberOfElements = DEFAULT_MAX_NUMBER_OF_ELEMENTS;
        try {
            if (cmd.hasOption("m")) {
                maxNumberOfElements = Integer.parseInt(cmd.getOptionValue("m"));
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid max number of elements specified for \"m\" option: " + cmd.getOptionValue("m"));
            System.exit(1);
        }

        int maxNumberOfLinksPerNode = DEFAULT_MAX_NUMBER_OF_LINKS_PER_NODE;
        try {
            if (cmd.hasOption("n")) {
                maxNumberOfLinksPerNode = Integer.parseInt(cmd.getOptionValue("n"));
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid max number of links per node specified for \"n\" option: " + cmd.getOptionValue("n"));
            System.exit(1);
        }

        return new Args(gsrsFile, outputDirectory, nestingLevel, maxNumberOfElements, maxNumberOfLinksPerNode);
    }

    private static void printHelpAndExit(Options options, HelpFormatter formatter, int code) {
        formatter.printHelp(" ", options);
        System.exit(code);
    }

    public static Map<String, String> getFullNodesCache(File gsrsDumpFile) throws IOException {
        Map<String, String> nodesCache = new LinkedHashMap<>();
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

    public static void generateNetworkFiles(Args parsedArgs, Map<String, String> nodesCache) throws IOException {
        File gsrsDumpFile = parsedArgs.gsrsFile;
        File outputDirectory = parsedArgs.outputDirectory;
        Integer nestingLevel = parsedArgs.nestingLevel;
        Integer maxNumberOfElements = parsedArgs.maxNumberOfElements;
        Integer maxNumberOfLinksPerNode = parsedArgs.maxNumberOfLinksPerNode;

        try (InputStream fis = new FileInputStream(gsrsDumpFile)) {
            try (InputStream in = new GZIPInputStream(fis)) {
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().forEach(p -> {
                    String gsrsJson = p.trim();
                    String uuid = getUuid(gsrsJson);

                    logger.debug("----------Processing uuid " + uuid + "----------");
                    try {
                        String networkJson = getNetworkJson(gsrsJson, nodesCache, nestingLevel, maxNumberOfElements, maxNumberOfLinksPerNode);
                        writeJsonFile(uuid, networkJson, outputDirectory);
                    } catch (JsonProcessingException e) {
                        logger.error("Unable to get json string from result object for uuid " + uuid);
                    }
                });
            }
        }
    }

    public static String getNetworkJson(
            String json, Map<String,
            String> nodesCache,
            Integer nestingLevel,
            Integer maxNumberOfElements,
            Integer maxNumberOfLinksPerNode
    ) throws JsonProcessingException {
        List allNodes = new ArrayList();
        List allLinks = new ArrayList();

        Map rootNode = getNode(json);
        allNodes.add(rootNode);

        Set<String> addedNodeUuids = new LinkedHashSet<>();
        addedNodeUuids.add((String) rootNode.get("id"));
        NetworkState state = new NetworkState(maxNumberOfElements, maxNumberOfLinksPerNode, addedNodeUuids);

        List prevLevelNodes = allNodes;
        for (int curNestingLevel = 0; curNestingLevel < nestingLevel; curNestingLevel++) {
            int shownLevel = curNestingLevel + 1;
            logger.debug("Getting nodes and links for level " + shownLevel);

            ImmutablePair<List, List> result = getNodesAndLinksForNodes(prevLevelNodes, nodesCache, state);

            List newLevelNodes = result.left;
            List newLevelLinks = result.right;
            allNodes.addAll(newLevelNodes);
            allLinks.addAll(newLevelLinks);

            boolean isLimitReached = state.maxNumberOfElements == 0;
            if (isLimitReached) {
                processListNodes(newLevelNodes);
                logger.debug("Reached maximum level of elements, level " + shownLevel + " will be skipped. Processing stopped.");
                break;
            }

            Boolean hasNewNodes = newLevelNodes.size() > 0;
            if (!hasNewNodes) {
                processListNodes(newLevelNodes);
                logger.debug("No nodes found for level " + shownLevel + ". Processing stopped.");
                break;
            }

            boolean isLastLevelReached = curNestingLevel == nestingLevel - 1;
            if (isLastLevelReached) {
                processListNodes(newLevelNodes);
            }

            prevLevelNodes = newLevelNodes;
        }

        Map network = new LinkedHashMap();
        network.put("nodes", allNodes);
        addLinkMeta(allLinks);
        network.put("links", allLinks);

        ImmutablePair tagsAndLegend = getTagsAndLegend(allNodes, allLinks);
        network.put("tags", tagsAndLegend.left);
        network.put("legend", tagsAndLegend.right);

        return new ObjectMapper().writeValueAsString(network);
    }

    private static ImmutablePair<List, Map> getTagsAndLegend(List<Map<String, Object>> nodes, List<Map<String, Object>> links) {
        List<Map<String, Object>> tags = new ArrayList<>();

        // "id" should be unique and consistent among tags, so "id" fields equals "text" field fits this.
        Map<String, Object> fetchedTag = new LinkedHashMap<>();
        fetchedTag.put("text", capitalizeFirstLetter(TAG_FETCHED));
        fetchedTag.put("id", TAG_FETCHED);
        tags.add(fetchedTag);

        Map<String, Object> unfetchedTag = new LinkedHashMap<>();
        unfetchedTag.put("text", capitalizeFirstLetter(TAG_UNFETCHED));
        unfetchedTag.put("id", TAG_UNFETCHED);
        tags.add(unfetchedTag);

        List<Map<String, Object>> legendNodes = new ArrayList<>();
        Set<String> nodeTypesSet = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String nodeType = (String) node.get("nodeType");
            if (!nodeTypesSet.contains(nodeType)) {
                nodeTypesSet.add(nodeType);

                final String capitalizedNodeType = capitalizeFirstLetter(nodeType);
                tags.add(new LinkedHashMap() {{
                    put("text", capitalizedNodeType);
                    put("id", nodeType);
                }});
                legendNodes.add(new LinkedHashMap() {{
                    put("text", capitalizedNodeType);
                    put("nodeType", nodeType);
                }});
            }
        }

        List<Map<String, Object>> legendLinks = new ArrayList<>();
        Set<String> linkTypesSet = new LinkedHashSet<>();
        for (Map<String, Object> link : links) {
            String linkType = (String) link.get("linkType");
            if (linkType != null && !linkTypesSet.contains(linkType)) {
                linkTypesSet.add(linkType);

                tags.add(new LinkedHashMap() {{
                    put("text", linkType);
                    put("id", linkType);
                }});
                legendLinks.add(new LinkedHashMap() {{
                    put("text", linkType);
                    put("linkType", linkType);
                }});
            }
        }

        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("nodes", legendNodes);
        legend.put("links", legendLinks);

        return ImmutablePair.of(tags, legend);
    }

    private static void addLinkMeta(List<Map<String, Object>> links) {
        LinkedHashMap<String, List<Map<String, Object>>> linksGroupedByPath = new LinkedHashMap<>();
        String linkPathDelimiter = "=|=";

        for (Map<String, Object> link : links) {
            String linkPath = link.get("source") + linkPathDelimiter + link.get("target");
            List<Map<String, Object>> pathLinks = linksGroupedByPath.get(linkPath);
            if (pathLinks == null) {
                pathLinks = new ArrayList<>();
                pathLinks.add(link);
                linksGroupedByPath.put(linkPath, pathLinks);
            } else {
                pathLinks.add(link);
            }
        }

        Set<String> processedReversedLinkPaths = new LinkedHashSet<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : linksGroupedByPath.entrySet()) {
            String linkPath = entry.getKey();
            if (processedReversedLinkPaths.contains(linkPath)) {
                continue;
            }

            String[] linkParts = linkPath.split(Pattern.quote(linkPathDelimiter));
            String source = linkParts[0];
            String target = linkParts[1];
            String reversedLinkPath = target + linkPathDelimiter + source;

            List<Map<String, Object>> pathLinks = entry.getValue();
            List<Map<String, Object>> reversedPathLinks = linksGroupedByPath.getOrDefault(reversedLinkPath, Collections.emptyList());
            List<Map<String, Object>> totalLinks = new ArrayList<>(pathLinks);
            totalLinks.addAll(reversedPathLinks);

            Integer linkTotal = totalLinks.size();
            boolean isNeededToSetLinkMeta = linkTotal > 1;
            if (isNeededToSetLinkMeta) {
                for (int i = 0; i < totalLinks.size(); i++) {
                    Map<String, Object> link = totalLinks.get(i);
                    link.put("linkTotal", linkTotal);
                    link.put("linkNumber", i);
                }
            }

            processedReversedLinkPaths.add(reversedLinkPath);
        }
    }

    public static void processFetchStatusForNode(Map<String, Object> node, String fetchStatus) {
        String newNodeType = node.get("nodeType") + " (" + fetchStatus + ")";
        node.put("nodeType", newNodeType);

        Set<String> tags = new LinkedHashSet<>();
        tags.add(newNodeType);
        tags.add(fetchStatus);
        node.put("tags", tags);
    }

    public static void processListNodes(List<Map<String, Object>> nodes) {
        for (Map<String, Object> node : nodes) {
            processFetchStatusForNode(node, TAG_UNFETCHED);

            Map nodeObj = (Map) node.get("obj");
            nodeObj.put("Number Of References", 0);
        }
    }

    public static ImmutablePair<List, List> getNodesAndLinksForNodes(List<Map<String, Object>> sourceNodes, Map<String, String> nodesCache, NetworkState state) {
        List newNodes = new ArrayList();
        List newLinks = new ArrayList();

        for (Map sourceNode : sourceNodes) {
            ImmutableTriple<Boolean, List, List> result = getNodesAndLinks(sourceNode, nodesCache, state);
            Boolean isFetched = result.left;
            if (!isFetched) {
                processFetchStatusForNode(sourceNode, TAG_UNFETCHED);

                boolean isLimitReached = state.maxNumberOfElements == 0;
                if (isLimitReached) {
                    return ImmutablePair.of(newNodes, newLinks);
                }
                continue;
            }

            processFetchStatusForNode(sourceNode, TAG_FETCHED);

            List<Map<String, Object>> nodes = result.middle;
            nodes.stream().forEach((Map n) -> state.addedNodeUuids.add((String) n.get("id")));
            List links = result.right;
            state.maxNumberOfElements -= nodes.size() + links.size();
            newNodes.addAll(nodes);
            newLinks.addAll(links);
        }

        return ImmutablePair.of(newNodes, newLinks);
    }


    public static ImmutableTriple<Boolean, List, List> getNodesAndLinks(Map sourceNode, Map<String, String> nodesCache, NetworkState state) {
        String sourceUuid = (String) sourceNode.get("id");

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> nodesUuids = new HashSet();
        int currentNumberOfElements = 0;
        int currentNumberOfLinks = 0;

        String sourceJson = nodesCache.get(sourceUuid);
        List<String> refuuidPaths = getRefuuidPaths(sourceJson);
        Integer numberOfReferences = refuuidPaths.size();
        logger.debug("Found " + numberOfReferences + " refuuids for uuid " + sourceUuid);
        Map sourceNodeObj = (Map) sourceNode.get("obj");

        for (String refuuidPath : refuuidPaths) {
            if (currentNumberOfLinks > state.maxNumberOfLinksPerNode || currentNumberOfElements > state.maxNumberOfElements) {
                sourceNodeObj.put("Number Of References", numberOfReferences + " (WARNING: this number exceeds the maximum number of links supported by this visualization)");
                return ImmutableTriple.of(false, nodes, links);
            }

            String targetUuid = getRefuuid(sourceJson, refuuidPath);
            String targetJson = nodesCache.get(targetUuid);
            if (targetJson == null) {
                logger.error("Unable to find json in cache for uuid " + targetUuid + " found by refuuidPath " + refuuidPath);
                continue;
            }

            boolean isSelfReference = sourceUuid.equals(targetUuid);
            boolean isAlreadyAdded = nodesUuids.contains(targetUuid) || state.addedNodeUuids.contains(targetUuid);
            if (isAlreadyAdded) {
//                logger.warn("Node with targetUuid: " + targetUuid + " is already added");
            } else if (isSelfReference) {
                logger.warn("Found self-reference for uuid: " + sourceUuid + ", refuuidPath: " + refuuidPath);
            } else {
                nodes.add(getNode(targetJson));
                nodesUuids.add(targetUuid);
                currentNumberOfElements++;
            }

            // self-reference link is valid and should be added among with normal links
            links.add(getLink(sourceJson, sourceUuid, targetUuid, refuuidPath));
            currentNumberOfElements++;
            currentNumberOfLinks++;
        }

        sourceNodeObj.put("Number Of References", currentNumberOfLinks);

        return ImmutableTriple.of(true, nodes, links);
    }

    public static List<String> getRefuuidPaths(String json) {
        Configuration conf = Configuration.builder().options(com.jayway.jsonpath.Option.AS_PATH_LIST).build();
        try {
            // Gets all object paths where refuuid is present
            return using(conf).parse(json).read("$.relationships..[?(@.refuuid)]");
        } catch (PathNotFoundException e) {
            // no refuuid paths found
            return Collections.emptyList();
        }
    }

    public static String getRefuuid(String json, String refuuidPath) {
        return (String) readJson(json, refuuidPath + ".refuuid");
    }

    public static String getUuid(String json) {
        return (String) readJson(json, "$.uuid");
    }

    public static Map<String, Object> getNode(String json) {
        Map<String, Object> nodeJson = new LinkedHashMap<>();

        String uuid = getUuid(json);
        nodeJson.put("id", uuid);

        List<String> displayNames = (List) readJson(json, "$.names[?(@.displayName == true)]..name");
        String n;
        if (displayNames.size() > 0) {
            n = displayNames.get(0);
        } else {
            List<String> preferredNames = (List) readJson(json, "$.names[?(@.preferred == true)]..name");
            if (preferredNames.size() > 0) {
                n = preferredNames.get(0);
            } else {
                n = (String) readJson(json, "$.names[0].name");
            }
        }
        nodeJson.put("n", n);

        String substanceClass = (String) readJson(json, "$.substanceClass");
        nodeJson.put("nodeType", substanceClass);

        Map<String, Object> nodeObj = new LinkedHashMap<>();
        nodeObj.put("UUID", uuid);
        nodeObj.put("Substance Class", substanceClass);
        nodeObj.put("Type", substanceClass);

        String approvalId = (String) readJson(json, "$.approvalID");
        if (approvalId != null) {
            nodeObj.put("UNII", approvalId);
        } else {
            List uniiCodes = (List) readJson(json, "$.codes[?(@.codeSystem == \"FDA UNII\")].code");
            nodeObj.put("UNII", uniiCodes.size() > 0 ? uniiCodes.get(0) : null);
        }

        nodeJson.put("obj", nodeObj);

        return nodeJson;
    }

    public static Map<String, Object> getLink(String sourceJson, String sourceUuid, String targetUuid, String refuuidPath) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("source", sourceUuid);
        link.put("target", targetUuid);

        // Parent obj is retrieved differently for paths with array and without array:
        // With array: "$['modifications']['structuralModifications'][0]['molecularFragment']" => "$['modifications']['structuralModifications'][0]
        // Without array: "$['mixture']['parentSubstance']" => "$['mixture']['parentSubstance']"
        Pattern withArrayPattern = Pattern.compile("\\[\\d+\\]\\['\\w+'\\]$");
        boolean hasArrayInPath = withArrayPattern.matcher(refuuidPath).find();
        String parentPath = hasArrayInPath ? refuuidPath.substring(0, refuuidPath.lastIndexOf("[")) : refuuidPath;
        Map parentObject = (Map) readJson(sourceJson, parentPath);

        String parentType = (String) parentObject.get("type");
        String name = (String) parentObject.get("name");
        String n = name != null ? name : parentType;
        putIfNotNull(link, "n", n);

        link.put("linkType", parentType);
        if (parentType != null) {
            Set<String> tags = new LinkedHashSet<>();
            tags.add(parentType);
            link.put("tags", tags);
        }

        Map<String, String> linkObj = new LinkedHashMap<>();
        linkObj.put("Link Type", parentType);
        String linkType = getLinkType(refuuidPath);
        putIfNotNull(linkObj, "TYPE", linkType);
        putIfNotNull(linkObj, "UUID", parentObject.get("uuid"));
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
