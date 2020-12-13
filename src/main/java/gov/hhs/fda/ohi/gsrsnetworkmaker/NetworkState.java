package gov.hhs.fda.ohi.gsrsnetworkmaker;

import java.util.Set;

public class NetworkState {
    public Integer maxNumberOfElements;
    public Integer maxNumberOfLinksPerNode;
    public Set<String> addedNodeUuids;

    public NetworkState(Integer maxNumberOfElements, Integer maxNumberOfLinksPerNode, Set<String> addedNodeUuids) {
        this.maxNumberOfElements = maxNumberOfElements;
        this.maxNumberOfLinksPerNode = maxNumberOfLinksPerNode;
        this.addedNodeUuids = addedNodeUuids;
    }
}
