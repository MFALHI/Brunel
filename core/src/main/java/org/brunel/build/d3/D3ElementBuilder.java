/*
 * Copyright (c) 2015 IBM Corporation and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.brunel.build.d3;

import org.brunel.build.d3.diagrams.D3Diagram;
import org.brunel.build.util.ElementDetails;
import org.brunel.build.util.ModelUtil;
import org.brunel.build.util.PositionFields;
import org.brunel.build.util.ScriptWriter;
import org.brunel.data.Dataset;
import org.brunel.data.Field;
import org.brunel.model.VisSingle;
import org.brunel.model.VisTypes;

import java.util.HashSet;
import java.util.Set;

class D3ElementBuilder {

    private final D3LabelBuilder labelBuilder;
    private final VisSingle vis;
    private final ScriptWriter out;
    private final D3ScaleBuilder scales;
    private final PositionFields positionFields;
    private final D3Diagram diagram;
    private Set<String> dimensionsAsRanges = new HashSet<String>();         // "x" or "y" if ranges on those dimensions

    public D3ElementBuilder(VisSingle vis, ScriptWriter out, D3ScaleBuilder scales,
                            PositionFields positionFields, Dataset data) {
        this.vis = vis;
        this.out = out;
        this.scales = scales;
        this.positionFields = positionFields;
        this.labelBuilder = new D3LabelBuilder(vis, out, data);
        this.diagram = D3Diagram.make(vis, data, out);
    }

    public void generate() {
        if (diagram != null) out.onNewLine().comment("Data structures for a", vis.tDiagram, "diagram");

        ElementDetails details = makeDetails();         // Create the details of what the element should be
        if (details.producesPath) definePath();         // Define paths needed in the element, and make data splits
        if (labelBuilder.needed()) labelBuilder.defineLabeling(details, vis.itemsLabel, false);   // Labels

        if (sizesNeeded()) {
            // Define size functions
            writeElementSize("x", positionFields.getX(vis), scales.getXExtent(), ModelUtil.getElementSize(vis, "width"));
            writeElementSize("y", positionFields.getY(vis), scales.getYExtent(), ModelUtil.getElementSize(vis, "height"));

        }

        modifyGroupStyleName();             // Diagrams change the name so CSS style sheets will work well

        // Define the data and main element into which shapes will be placed
        out.add("var d3Data =", details.dataSource).endStatement();

        out.add("var element = main.selectAll('*').data(d3Data,", getKeyFunction(), ")").endStatement();

        // Define what happens when data is added ('enter')
        out.add("element.enter().append('" + details.elementType + "')");
        out.add(".attr('class', ", details.classes, ")");

        if (diagram != null) diagram.writeDiagramEnter();
        else writeCoordEnter();

        // When data changes (including being added) update the items
        // These fire for both 'enter' and 'update' data
        out.add("BrunelD3.trans(element,transitionMillis)");

        if (diagram != null) {
            diagram.writeDefinition(details);
        } else {
            writeCoordinateDefinition(details);
            writeCoordinateLabelingAndAesthetics(details);
        }

        // This fires when items leave the system
        out.onNewLine().ln().add("BrunelD3.trans(element.exit(),transitionMillis/3)");
        out.addChained("style('opacity', 0.5).remove()").endStatement();
    }

    public void writeCoordinateFunction(String name, Field[] f, boolean categorical, ScriptWriter out) {
        String scaleName = "scale_" + name;

        out.onNewLine().add("var", name, "= function(d) { return ");

        if (f.length == 0) {
            out.add(scaleName, "(0.5) }").endStatement();            // No field on this dimension -- use a default 0/1 scale
        } else if (f.length == 1) {
            // Just one field -- the usual case
            Field field = f[0];
            String dataFunction = D3Util.writeCall(f[0]);
            if (isRange(field)) {
                // For a range value which has not been stacked, we use its extent
                out.add(scaleName, "(" + dataFunction + ".extent()) }").endStatement();
            } else if (field.isBinned() && !categorical) {
                // A Binned value on a non-categorical axes
                // Write both the center AND low and high extents
                out.add(scaleName, "(" + dataFunction + ".mid()) }").endStatement();
                out.add("var", name + "0 = function(d) { return ")
                        .add(scaleName, "(" + dataFunction + ".low) }").endStatement();
                out.add("var", name + "1 = function(d) { return ")
                        .add(scaleName, "(" + dataFunction + ".high) }").endStatement();
                dimensionsAsRanges.add(name);
            } else {
                // Nothing unusual
                out.add(scaleName, "(" + dataFunction + ") }").endStatement();
            }

        } else {
            // The dimension contains two fields: a range

            if (name.equals("x")) {
                // For an 'x' dimension the single-value function averages the upper and lower functions
                out.add("(" + name + "1(d) + " + name + "0(d)) / 2 }").endStatement();
            } else {
                // For a 'y' dimension the single-value function equals the upper function
                out.add(name + "1(d) }").endStatement();
            }

            // Define the lower part: scale the value (if it is a range, extract the low part of the range)
            out.onNewLine().add("var", name + "0 = function(d) { return", scaleName, "(" + D3Util.writeCall(f[0]));
            if (isRange(f[0])) out.add(".low");
            out.add(")}").endStatement();

            // Define the upper part: scale the value (if it is a range, extract the high part of the range)
            out.onNewLine().add("var", name + "1 = function(d) { return", scaleName, "(" + D3Util.writeCall(f[1]));
            if (isRange(f[1])) out.add(".high");
            out.add(") }").endStatement();

            dimensionsAsRanges.add(name);
        }
    }

    private void modifyGroupStyleName() {
        // Define the main element class
        if (diagram != null)
            out.add("main.attr('class',", diagram.getStyleClasses(), ")").endStatement();
    }

    private void writeCoordEnter() {
        // Added rounded styling if needed
        ModelUtil.Size size = ModelUtil.getRoundRectangleRadius(vis);
        if (size != null)
            out.addChained("attr('rx'," + size.valueInPixels(8) + ").attr('ry', " + size.valueInPixels(8) + ")").ln();
        out.endStatement().onNewLine().ln();
    }

    private void constructSplitPath() {
        // We add the x function to signal we need the paths sorted
        String params = "data, path";
        if (vis.tElement == VisTypes.Element.line || vis.tElement == VisTypes.Element.area)
            params += ", x";
        out.add("var splits = BrunelD3.makePathSplits(" + params + ");").ln();
    }

    private void defineCircle() {
        // X and Y are simple
        out.addChained("attr('cx',x).attr('cy', y)");
        // Radius is based on the size available
        out.addChained("attr('r', function(d) { return Math.min(size_x(d), size_y(d)) / 2})");
    }

    private void definePath() {
        // First deal with the case of wedges (polar intervals)
        if (vis.tElement == VisTypes.Element.bar && vis.coords == VisTypes.Coordinates.polar) {
            out.add("var path = d3.svg.arc().outerRadius(geom.inner_radius).innerRadius(0)").ln();
            out.addChained("outerRadius(geom.inner_radius).innerRadius(0)").ln();
            if (vis.fRange == null && !vis.stacked)
                out.addChained("startAngle(0).endAngle(y)");
            else
                out.addChained("startAngle(y0).endAngle(y1)");
            out.endStatement();
            return;
        }
        // Now actual paths
        if (vis.tElement == VisTypes.Element.area) {
            if (vis.fRange == null && !vis.stacked)
                out.add("var path = d3.svg.area().x(x).y1(y).y0(function(d) { return scale_y(0) })");
            else
                out.add("var path = d3.svg.area().x(x).y1(y1).y0(y0)");
        } else if (vis.tElement.producesSingleShape) {
            out.add("var path = d3.svg.line().x(x).y(y)");
        }
        if (vis.tUsing == VisTypes.Using.interpolate) {
            out.add(".interpolate('cardinal')");
        }
        out.endStatement();
        constructSplitPath();
    }

    private void defineRectCenteredAtY() {
        out.addChained("attr('y', function(d) { return y(d) - size_y(d)/2 })");
        out.addChained("attr('height', size_y)");
    }

    private void defineRectFromYToAxis() {
        if (vis.fRange == null && !vis.stacked) {
            // Simple element; drop y to baseline
            if (vis.coords == VisTypes.Coordinates.transposed) {
                out.addChained("attr('y', 0)")
                        .addChained("attr('height', function(d) { return Math.max(0,y(d)) })");
            } else {
                out.addChained("attr('y', y)")
                        .addChained("attr('height', function(d) {return Math.max(0,geom.inner_height - y(d)) }) ");
            }
        } else {
            // Range element goes from higher of the pair of values to the lower
            // Stacked does the same
            out.addChained("attr('y', function(d) { return Math.min(y0(d), y1(d)) } )");
            out.addChained("attr('height', function(d) {return Math.abs(y0(d) - y1(d)) })");
        }

    }

    private void defineRectHorizontalExtent() {
        // Sadly, browsers are inconsistent in how they handle width. It can be considered either a style or a
        // positional attribute, so we need to specify as both to make all browsers happy

        if (dimensionsAsRanges.contains("x")) {
            // Defined by an extent on this axis
            out.addChained("attr('x', x0)");
            out.addChained("attr('width', function(d) { return x1(d) - x0(d) })");
            out.addChained("style('width', function(d) { return x1(d) - x0(d) })");
        } else {
            out.addChained("attr('x', function(d) { return x(d) - size_x(d)/2 })");
            out.addChained("attr('width', size_x)");
            out.addChained("style('width', size_x)");
        }
    }

    private void defineText() {
        // X and Y are simple
        out.addChained("attr('x',x).attr('y', y).attr('dy', '0.35em').text(labeling.content)");
        labelBuilder.addFontSizeAttribute(vis);
    }

    /* The key function ensure we have object constancy when animating */
    private String getKeyFunction() {
        String content = diagram != null ? diagram.getRowKey() : "d.key";
        return "function(d) { return " + content + "}";
    }

    private String getSymbol() {
        String result = ModelUtil.getElementSymbol(vis);
        if (result != null) return result;
        // We default to a rectangle if all the scales are categorical, otherwise we util a point
        if (positionFields.allXFields.length == 0 || positionFields.allYFields.length == 0) return "point";
        return positionFields.xCategorical && positionFields.yCategorical ? "rect" : "point";
    }

    private boolean isRange(Field field) {
        String s = field.stringProperty("summary");
        return s != null && (s.equals("iqr") || s.equals("range"));
    }

    private ElementDetails makeDetails() {
        // When we create diagrams this has the side effect of writing the data calls needed
        return diagram == null ? ElementDetails.makeForCoordinates(vis, getSymbol()) : diagram.writeDataConstruction();
    }

    private boolean sizesNeeded() {
        if (diagram != null) return diagram.showsElement();         // Diagrams that show elements need it
        return vis.tElement != VisTypes.Element.area;               // Only areas cannot util a size at all
    }

    private void writeCoordinateDefinition(ElementDetails details) {
        if (details.splitIntoShapes)
            out.addChained("attr('d', function(d) { return d.path })");     // Split path -- get it from the split
        else if (details.producesPath)
            out.addChained("attr('d', path)");                              // Simple path -- just util it
        else {
            // Not a path; one shape per row
            if (details.elementType.equals("rect"))
                defineRectHorizontalExtent();
            if (vis.tElement == VisTypes.Element.bar)
                defineRectFromYToAxis();
            else if (details.elementType.equals("rect"))
                defineRectCenteredAtY();
            else if (vis.tElement == VisTypes.Element.text)
                defineText();
            else
                defineCircle();
        }
    }

    private void writeCoordinateLabelingAndAesthetics(ElementDetails details) {
        // Define colors using the color function
        if (!vis.fColor.isEmpty()) out.addChained("style(" + details.colorAttribute + ", color)");

        // Define line width if needed
        if (!vis.fSize.isEmpty() && (vis.tElement == VisTypes.Element.line || vis.tElement == VisTypes.Element.edge
                || vis.tElement == VisTypes.Element.path)) {
            out.addChained("style('stroke-width', size)");
        }

        // Define opacity
        if (!vis.fOpacity.isEmpty()) {
            out.addChained("style('fill-opacity', opacity)").addChained("style('stroke-opacity', opacity)");
        }

        out.endStatement();

        labelBuilder.addTooltips(details);

        // We do not add labels if the element IS a label
        if (labelBuilder.needed() && vis.tElement != VisTypes.Element.text) {
            labelBuilder.addLabels(details);
        }
    }

    private void writeElementSize(String name, Field[] fields, String extent, ModelUtil.Size size) {

        out.add("function", "size_" + name + "(d) { return ");
        // Add in multipliers for size fields, if they are defined
        if (vis.fSize.size() == 1) {
            // Both height and width util the same one
            out.add("size(d) * ");
        } else if (vis.fSize.size() == 2) {
            // width and height are different
            if (name.equals("x")) out.add("width(d) * ");
            else out.add("height(d) * ");
        }

        if (size != null && size.isPercent()) {
            out.add(size.value(), "* ");
        }

        if (size != null && !size.isPercent()) {
            // Absolute size overrides everything
            out.add(size.value());
        } else if (fields.length == 0) {
            // If there are no fields, then fill the extent completely
            out.add(extent);
        } else {
            int categories = scales.getCategories(fields).size();
            if (categories > 0) {
                // Fill a category span (or 90% of it for categorical fields when percent not defined)
                if ((size == null || !size.isPercent()) && !scales.allNumeric(fields))
                    out.add("0.9 * ");
                out.add(extent, "/", categories);
            } else {
                // Need to define size in terms of the spacing -- or default if granularity is too small
                Double spacing = fields[0].numericProperty("granularity");
                if (spacing != null && spacing > (fields[0].max() - fields[0].min()) / 20)
                    out.add("Math.abs(scale_" + name + "(" + spacing + ")-scale_" + name + "(0))");
                else
                    out.add("geom.default_point_size");
            }
        }

        out.add(" }").endStatement();
    }

}
