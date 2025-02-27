/*
 * Copyright (c) 2015 IBM Corporation and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.brunel.build.d3.diagrams;

import org.brunel.build.d3.element.ElementDetails;
import org.brunel.build.d3.element.ElementRepresentation;
import org.brunel.build.info.ElementStructure;
import org.brunel.build.util.ScriptWriter;
import org.brunel.data.Dataset;
import org.brunel.model.VisSingle;

class Network extends D3Diagram {

    private final ElementStructure nodes;
    private final ElementStructure edges;
    private final String nodeID, fromFieldID, toFieldID;

    public Network(VisSingle vis, Dataset data, ElementStructure nodes, ElementStructure edges, ScriptWriter out) {
        super(vis, data, out);
        this.nodes = nodes;
        this.edges = edges;

        if (vis.fKeys.size() > 0) {
            nodeID  = vis.fKeys.get(0).asField(nodes.data);
        } else if (vis.fY.size() > 1) {
            nodeID  = "#values";
        } else if (vis.positionFields().length > 0) {
            nodeID  = vis.positionFields()[0];
        } else {
            throw new IllegalStateException("Networks require nodes to have a key field or position field");
        }

        VisSingle edgesVis = edges.vis;
        if (edgesVis.fKeys.size() > 1) {
            fromFieldID  = edgesVis.fKeys.get(0).asField(edges.data);
            toFieldID  = edgesVis.fKeys.get(1).asField(edges.data);
        } else if (edgesVis.positionFields().length > 1) {
            fromFieldID  = edgesVis.positionFields()[0];
            toFieldID  = edgesVis.positionFields()[1];
        } else {
            throw new IllegalStateException("Networks require edges to have two key fields or position fields");
        }
    }

    public void writeBuildCommands() {
        String density = "";
        if (vis.tDiagramParameters.length > 0) density = ", " + vis.tDiagramParameters[0].asDouble();
        out.onNewLine().add("if (first) ")
                .add("BrunelD3.network(d3.layout.force(), graph, elements[" + nodes.index
                        + "], elements[" + edges.index + "], geom" + density + ")").endStatement();
    }

    public void writePerChartDefinitions() {
        out.add("var graph;").at(50).comment("Graph used for nodes and links");
    }

    public ElementDetails initializeDiagram() {
        String edgeDataset = "elements[" + edges.getBaseDatasetIndex() + "].data()";
        String nodeField = quoted(nodeID);

        String from = quoted(fromFieldID),to = quoted(toFieldID);
        out.add("graph = BrunelData.diagram_Graph.make(processed,", nodeField, ",",
                edgeDataset, ",", from, ",", to, ")").endStatement();
        out.ln();
        makeLayout();
        out.ln();
        return ElementDetails.makeForDiagram(vis, ElementRepresentation.largeCircle, "point", "graph.nodes");
    }

    private void makeLayout() {
        out.comment("Initial Circle Layout")
                .add("var h = geom.inner_width/2, v = geom.inner_height/2, a, i, N = graph.nodes.length").endStatement()
                .add("for(i=0; i<N; i++) { a = Math.PI*2*i/N; graph.nodes[i].x = h + h*Math.cos(a)/2; graph.nodes[i].y = v + v*Math.sin(a)/2 }")
                .ln();
    }

    public void writeDefinition(ElementDetails details) {
        out.addChained("attr('r',", details.overallSize, ")").endStatement();
        addAestheticsAndTooltips(details, true);
    }

}
