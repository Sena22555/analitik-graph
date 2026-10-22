package com.analytics.model;

import java.util.ArrayList;
import java.util.List;

/** Compact graph of statistically detected relationships. */
public class PatternGraph {
    private List<PatternNode> nodes = new ArrayList<>();
    private List<PatternLink> links = new ArrayList<>();

    public List<PatternNode> getNodes() { return nodes; }
    public void setNodes(List<PatternNode> nodes) { this.nodes = nodes; }
    public List<PatternLink> getLinks() { return links; }
    public void setLinks(List<PatternLink> links) { this.links = links; }
}
