use std::borrow::Cow;
use std::collections::HashMap;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use parley::fontique::Blob;
use parley::style::{FontFamily, FontStack, FontWeight, FontStyle, StyleProperty};
use parley::{FontContext, Layout, LayoutContext, LineHeight, Alignment, AlignmentOptions};
use skrifa::MetadataProvider;
use skrifa::raw::types::NameId;

use crate::model::{PdfColor, PdfElement};
use crate::tree::*;

// ── Layout output ───────────────────────────────────────────────────────────

/// A positioned piece produced by the layout engine.
/// Text is kept as a parley `Layout` so the renderer can call `draw_glyphs()`.
/// Everything else is a plain `PdfElement` for backward-compatible rendering.
pub enum PositionedItem {
    /// Rich text laid out by parley.  Renderer iterates lines/runs/glyphs.
    RichText {
        text: String,
        layout: Layout<PdfColor>,
        x: f32,
        y: f32,
    },
    /// Flat element (rect, line, image, svg, clip, etc.) — already has absolute coords.
    Element(PdfElement),
}

pub struct LayoutResult {
    pub items: Vec<PositionedItem>,
    pub height: f32,
}

// ── Font context setup ──────────────────────────────────────────────────────

/// Read the font family name from OpenType/TrueType font data.
fn read_family_name(data: &[u8]) -> Option<String> {
    let font = skrifa::FontRef::from_index(data, 0).ok()?;
    font.localized_strings(NameId::FAMILY_NAME)
        .english_or_first()
        .map(|s| s.chars().collect())
}

/// Register all document fonts with parley's fontique collection.
/// Returns (blob map, name map) where name map translates user-provided names
/// to actual font family names discovered from the font binary.
pub fn register_fonts(
    font_cx: &mut FontContext,
    fonts: &HashMap<String, crate::model::PdfFontDef>,
) -> (HashMap<String, (Blob<u8>, u32)>, HashMap<String, String>) {
    let mut blobs = HashMap::new();
    let mut name_map = HashMap::new();
    for (name, def) in fonts {
        if let Ok(bytes) = BASE64.decode(&def.data) {
            if let Some(actual_name) = read_family_name(&bytes) {
                if &actual_name != name {
                    name_map.insert(name.clone(), actual_name);
                }
            }
            let blob: Blob<u8> = Vec::into(bytes);
            font_cx.collection.register_fonts(blob.clone(), None);
            blobs.insert(name.clone(), (blob, 0u32));
        }
    }
    (blobs, name_map)
}

// ── Layout engine ───────────────────────────────────────────────────────────

pub struct LayoutEngine<'a> {
    font_cx: &'a mut FontContext,
    layout_cx: &'a mut LayoutContext<PdfColor>,
    default_font: String,
    font_name_map: HashMap<String, String>,
}

impl<'a> LayoutEngine<'a> {
    pub fn new(
        font_cx: &'a mut FontContext,
        layout_cx: &'a mut LayoutContext<PdfColor>,
        default_font: String,
        font_name_map: HashMap<String, String>,
    ) -> Self {
        Self { font_cx, layout_cx, default_font, font_name_map }
    }

    /// Resolve a user-provided font name to the actual font family name
    /// that parley/fontique discovered from the font binary.
    fn resolve_font<'b>(&'b self, font: &'b str) -> &'b str {
        let name = if font.is_empty() { &self.default_font } else { font };
        self.font_name_map.get(name).map(|s| s.as_str()).unwrap_or(name)
    }

    /// Lay out a full page's content.  Returns positioned items and total height consumed.
    pub fn layout_nodes(
        &mut self,
        nodes: &[DocumentNode],
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let mut items = Vec::new();
        let mut cur_y = y;
        for node in nodes {
            let result = self.layout_node(node, x, cur_y, max_width);
            cur_y += result.height;
            items.extend(result.items);
        }
        LayoutResult { items, height: cur_y - y }
    }

    /// Lay out a single document node at the given position.
    pub fn layout_node(
        &mut self,
        node: &DocumentNode,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        match node {
            DocumentNode::Text {
                content, font_size, font, color, align, bold, italic, line_spacing,
            } => self.layout_text(content, *font_size, font, color, align, *bold, *italic, false, *line_spacing, x, y, max_width),

            DocumentNode::Paragraph {
                content, font_size, font, color, align, line_spacing, bold, markdown,
            } => self.layout_text(content, *font_size, font, color, align, *bold, false, *markdown, *line_spacing, x, y, max_width),

            DocumentNode::RichParagraph {
                segments, font_size, font, color, line_spacing,
            } => self.layout_rich_paragraph(segments, *font_size, font, color, *line_spacing, x, y, max_width),

            DocumentNode::BulletList {
                items: list_items, bullet_color, font_size, font, color, line_spacing,
                split_strategy: _, bullet_indent, item_gap, markdown,
            } => self.layout_bullet_list(list_items, bullet_color, *font_size, font, color, *line_spacing, *bullet_indent, *item_gap, *markdown, x, y, max_width),

            DocumentNode::RichBulletList {
                items: list_items, bullet_color, font_size, font, color, line_spacing,
                split_strategy: _, bullet_indent, item_gap,
            } => self.layout_rich_bullet_list(list_items, bullet_color, *font_size, font, color, *line_spacing, *bullet_indent, *item_gap, x, y, max_width),

            DocumentNode::Spacer { height } => {
                LayoutResult { items: vec![], height: *height }
            }

            DocumentNode::Divider { color, stroke_width } => {
                self.layout_divider(color, *stroke_width, x, y, max_width)
            }

            DocumentNode::Rect {
                width, height, fill_color, stroke_color, stroke_width, corner_radius,
            } => {
                let w = width.unwrap_or(max_width);
                let rect_x = x + (max_width - w) / 2.0;
                LayoutResult {
                    items: vec![PositionedItem::Element(PdfElement::Rect {
                        x: rect_x, y, width: w, height: *height,
                        fill_color: fill_color.clone(),
                        stroke_color: stroke_color.clone(),
                        stroke_width: *stroke_width,
                        corner_radius: *corner_radius,
                    })],
                    height: *height,
                }
            }

            DocumentNode::Image { data, width, height, align, format } => {
                let img_x = align_x(x, max_width, *width, align);
                LayoutResult {
                    items: vec![PositionedItem::Element(PdfElement::Image {
                        x: img_x, y, width: *width, height: *height,
                        data: data.clone(), format: format.clone(),
                    })],
                    height: *height,
                }
            }

            DocumentNode::Svg { content, width, height, align } => {
                let svg_x = align_x(x, max_width, *width, align);
                LayoutResult {
                    items: vec![PositionedItem::Element(PdfElement::Svg {
                        x: svg_x, y, width: *width, height: *height,
                        content: content.clone(),
                    })],
                    height: *height,
                }
            }

            DocumentNode::Column { gap, alignment, split_strategy: _, children } => {
                self.layout_column(children, *gap, alignment, x, y, max_width)
            }

            DocumentNode::Row { gap, alignment, cells } => {
                self.layout_row(cells, *gap, alignment, x, y, max_width)
            }

            DocumentNode::Padded { padding, background, corner_radius, children } => {
                self.layout_padded(children, padding, background.as_ref(), *corner_radius, x, y, max_width)
            }

            DocumentNode::AccentBar { color, bar_width, background, corner_radius, padding: pad, children } => {
                self.layout_accent_bar(children, color, *bar_width, background.as_ref(), *corner_radius, *pad, x, y, max_width)
            }

            DocumentNode::Grid { columns, cell_padding, border_color, rows } => {
                self.layout_grid(columns, cell_padding, border_color.as_ref(), rows, x, y, max_width)
            }

            DocumentNode::Stack { alignment, children } => {
                self.layout_stack(children, alignment, x, y, max_width)
            }

            DocumentNode::Canvas { height, elements } => {
                let items: Vec<PositionedItem> = elements.iter()
                    .map(|e| PositionedItem::Element(offset_element(e, x, y)))
                    .collect();
                LayoutResult { items, height: *height }
            }

            DocumentNode::Overlay { elements } => {
                let items: Vec<PositionedItem> = elements.iter()
                    .map(|e| PositionedItem::Element(offset_element(e, x, y)))
                    .collect();
                LayoutResult { items, height: 0.0 }
            }
        }
    }

    // ── Text layout helpers ─────────────────────────────────────────────

    fn layout_text(
        &mut self,
        content: &str,
        font_size: f32,
        font: &str,
        color: &PdfColor,
        align: &TextAlign,
        bold: bool,
        italic: bool,
        markdown: bool,
        line_spacing: f32,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        // When markdown is enabled, parse with pulldown-cmark to extract
        // plain text and inline style ranges (bold, italic, etc.)
        let (text, md_bold, md_italic) = if markdown {
            use pulldown_cmark::{Event, Parser, Tag, TagEnd};

            let mut plain = String::new();
            let mut bold_ranges: Vec<std::ops::Range<usize>> = Vec::new();
            let mut italic_ranges: Vec<std::ops::Range<usize>> = Vec::new();
            let mut bold_start: Option<usize> = None;
            let mut italic_start: Option<usize> = None;

            for event in Parser::new(content) {
                match event {
                    Event::Start(Tag::Strong) => bold_start = Some(plain.len()),
                    Event::End(TagEnd::Strong) => {
                        if let Some(s) = bold_start.take() {
                            if s < plain.len() { bold_ranges.push(s..plain.len()); }
                        }
                    }
                    Event::Start(Tag::Emphasis) => italic_start = Some(plain.len()),
                    Event::End(TagEnd::Emphasis) => {
                        if let Some(s) = italic_start.take() {
                            if s < plain.len() { italic_ranges.push(s..plain.len()); }
                        }
                    }
                    Event::Text(t) | Event::Code(t) => plain.push_str(&t),
                    Event::SoftBreak => plain.push(' '),
                    Event::HardBreak => plain.push('\n'),
                    _ => {}
                }
            }
            (plain, bold_ranges, italic_ranges)
        } else {
            (content.to_string(), vec![], vec![])
        };

        if text.is_empty() {
            return LayoutResult { items: vec![], height: 0.0 };
        }

        let font_name = self.resolve_font(font).to_string();
        let mut builder = self.layout_cx.ranged_builder(&mut self.font_cx, &text, 1.0, false);

        builder.push_default(StyleProperty::FontSize(font_size));
        builder.push_default(StyleProperty::Brush(color.clone()));
        builder.push_default(StyleProperty::LineHeight(LineHeight::FontSizeRelative(line_spacing)));
        builder.push_default(StyleProperty::FontStack(FontStack::List(
            Cow::Owned(vec![FontFamily::Named(Cow::Owned(font_name))])
        )));
        if bold {
            builder.push_default(StyleProperty::FontWeight(FontWeight::new(700.0)));
        }
        if italic {
            builder.push_default(StyleProperty::FontStyle(FontStyle::Italic));
        }
        for range in &md_bold {
            builder.push(StyleProperty::FontWeight(FontWeight::new(700.0)), range.clone());
        }
        for range in &md_italic {
            builder.push(StyleProperty::FontStyle(FontStyle::Italic), range.clone());
        }

        let mut layout = builder.build(&text);
        layout.break_all_lines(Some(max_width));
        layout.align(Some(max_width), to_parley_align(align), AlignmentOptions::default());

        let height = layout.height();
        LayoutResult {
            items: vec![PositionedItem::RichText {
                text,
                layout,
                x,
                y,
            }],
            height,
        }
    }

    fn layout_rich_paragraph(
        &mut self,
        segments: &[RichSegment],
        font_size: f32,
        font: &str,
        color: &PdfColor,
        line_spacing: f32,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let full_text: String = segments.iter().map(|s| s.text.as_str()).collect();
        if full_text.is_empty() {
            return LayoutResult { items: vec![], height: 0.0 };
        }

        let font_name = self.resolve_font(font).to_string();
        let mut builder = self.layout_cx.ranged_builder(&mut self.font_cx, &full_text, 1.0, false);

        builder.push_default(StyleProperty::FontSize(font_size));
        builder.push_default(StyleProperty::Brush(color.clone()));
        builder.push_default(StyleProperty::LineHeight(LineHeight::FontSizeRelative(line_spacing)));
        builder.push_default(StyleProperty::FontStack(FontStack::List(
            Cow::Owned(vec![FontFamily::Named(Cow::Owned(font_name))])
        )));

        let mut offset = 0;
        for seg in segments {
            if seg.bold {
                let range = offset..offset + seg.text.len();
                builder.push(StyleProperty::FontWeight(FontWeight::new(700.0)), range);
            }
            offset += seg.text.len();
        }

        let mut layout = builder.build(&full_text);
        layout.break_all_lines(Some(max_width));
        layout.align(Some(max_width), Alignment::Start, AlignmentOptions::default());

        let height = layout.height();
        LayoutResult {
            items: vec![PositionedItem::RichText {
                text: full_text,
                layout,
                x,
                y,
            }],
            height,
        }
    }

    fn layout_bullet_list(
        &mut self,
        items: &[String],
        bullet_color: &PdfColor,
        font_size: f32,
        font: &str,
        color: &PdfColor,
        line_spacing: f32,
        bullet_indent: f32,
        item_gap: f32,
        markdown: bool,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let mut all_items = Vec::new();
        let mut cur_y = y;
        let bullet_str = "\u{2022}";

        for (i, item) in items.iter().enumerate() {
            let bullet_result = self.layout_text(
                bullet_str, font_size, font, bullet_color,
                &TextAlign::Left, false, false, false, line_spacing,
                x, cur_y, bullet_indent,
            );
            all_items.extend(bullet_result.items);

            let text_result = self.layout_text(
                item, font_size, font, color,
                &TextAlign::Left, false, false, markdown, line_spacing,
                x + bullet_indent, cur_y, max_width - bullet_indent,
            );
            let item_height = text_result.height.max(bullet_result.height);
            all_items.extend(text_result.items);

            cur_y += item_height;
            if i + 1 < items.len() {
                cur_y += item_gap;
            }
        }

        LayoutResult { items: all_items, height: cur_y - y }
    }

    fn layout_rich_bullet_list(
        &mut self,
        items: &[Vec<RichSegment>],
        bullet_color: &PdfColor,
        font_size: f32,
        font: &str,
        color: &PdfColor,
        line_spacing: f32,
        bullet_indent: f32,
        item_gap: f32,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let mut all_items = Vec::new();
        let mut cur_y = y;
        let bullet_str = "\u{2022}";

        for (i, segments) in items.iter().enumerate() {
            let bullet_result = self.layout_text(
                bullet_str, font_size, font, bullet_color,
                &TextAlign::Left, false, false, false, line_spacing,
                x, cur_y, bullet_indent,
            );
            all_items.extend(bullet_result.items);

            let text_result = self.layout_rich_paragraph(
                segments, font_size, font, color, line_spacing,
                x + bullet_indent, cur_y, max_width - bullet_indent,
            );
            let item_height = text_result.height.max(bullet_result.height);
            all_items.extend(text_result.items);

            cur_y += item_height;
            if i + 1 < items.len() {
                cur_y += item_gap;
            }
        }

        LayoutResult { items: all_items, height: cur_y - y }
    }

    fn layout_divider(
        &self,
        color: &PdfColor,
        stroke_width: f32,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let mid_y = y + stroke_width / 2.0;
        LayoutResult {
            items: vec![PositionedItem::Element(PdfElement::Line {
                x1: x, y1: mid_y, x2: x + max_width, y2: mid_y,
                color: color.clone(),
                stroke_width,
                ripple: 0.0,
                thickness_ripple: 0.0,
            })],
            height: stroke_width,
        }
    }

    // ── Container layout helpers ────────────────────────────────────────

    fn layout_column(
        &mut self,
        children: &[DocumentNode],
        gap: f32,
        _alignment: &HorizontalAlignment,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let mut items = Vec::new();
        let mut cur_y = y;
        for (i, child) in children.iter().enumerate() {
            let result = self.layout_node(child, x, cur_y, max_width);
            cur_y += result.height;
            items.extend(result.items);
            if i + 1 < children.len() {
                cur_y += gap;
            }
        }
        LayoutResult { items, height: cur_y - y }
    }

    fn layout_row(
        &mut self,
        cells: &[RowCell],
        gap: f32,
        alignment: &VerticalAlignment,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        // Calculate cell widths
        let total_gap = gap * (cells.len().saturating_sub(1)) as f32;
        let available = max_width - total_gap;

        let mut fixed_total = 0.0f32;
        let mut weight_total = 0.0f32;
        for cell in cells {
            if let Some(fw) = cell.fixed_width {
                fixed_total += fw;
            } else {
                weight_total += cell.weight.unwrap_or(1.0);
            }
        }
        let flexible_space = (available - fixed_total).max(0.0);

        let cell_widths: Vec<f32> = cells.iter().map(|cell| {
            if let Some(fw) = cell.fixed_width {
                fw
            } else {
                let w = cell.weight.unwrap_or(1.0);
                if weight_total > 0.0 { flexible_space * w / weight_total } else { 0.0 }
            }
        }).collect();

        // First pass: layout all cells to determine max height
        let mut cell_results: Vec<LayoutResult> = Vec::new();
        let mut cell_xs: Vec<f32> = Vec::new();
        let mut cur_x = x;
        let mut max_height = 0.0f32;

        for (i, cell) in cells.iter().enumerate() {
            cell_xs.push(cur_x);
            let result = self.layout_nodes(&cell.children, cur_x, y, cell_widths[i]);
            max_height = max_height.max(result.height);
            cell_results.push(result);
            cur_x += cell_widths[i] + gap;
        }

        // Second pass: apply vertical alignment offset
        let mut items = Vec::new();
        for result in cell_results.into_iter() {
            let offset_y = match alignment {
                VerticalAlignment::Top => 0.0,
                VerticalAlignment::CenterVertically => (max_height - result.height) / 2.0,
                VerticalAlignment::Bottom => max_height - result.height,
            };
            if offset_y.abs() > 0.001 {
                for item in result.items {
                    items.push(offset_item(item, 0.0, offset_y));
                }
            } else {
                items.extend(result.items);
            }
        }

        LayoutResult { items, height: max_height }
    }

    fn layout_padded(
        &mut self,
        children: &[DocumentNode],
        padding: &Padding,
        background: Option<&PdfColor>,
        corner_radius: f32,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let inner_x = x + padding.left;
        let inner_y = y + padding.top;
        let inner_width = (max_width - padding.left - padding.right).max(0.0);

        let content = self.layout_nodes(children, inner_x, inner_y, inner_width);
        let total_height = padding.top + content.height + padding.bottom;

        let mut items = Vec::new();
        if let Some(bg) = background {
            items.push(PositionedItem::Element(PdfElement::Rect {
                x, y, width: max_width, height: total_height,
                fill_color: Some(bg.clone()),
                stroke_color: None,
                stroke_width: 0.0,
                corner_radius,
            }));
        }
        items.extend(content.items);

        LayoutResult { items, height: total_height }
    }

    fn layout_accent_bar(
        &mut self,
        children: &[DocumentNode],
        color: &PdfColor,
        bar_width: f32,
        background: Option<&PdfColor>,
        corner_radius: f32,
        padding: f32,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        let inner_x = x + bar_width + padding;
        let inner_y = y + padding;
        let inner_width = (max_width - bar_width - padding * 2.0).max(0.0);

        let content = self.layout_nodes(children, inner_x, inner_y, inner_width);
        let total_height = content.height + padding * 2.0;

        let mut items = Vec::new();

        // Background rect
        if let Some(bg) = background {
            items.push(PositionedItem::Element(PdfElement::Rect {
                x, y, width: max_width, height: total_height,
                fill_color: Some(bg.clone()),
                stroke_color: None,
                stroke_width: 0.0,
                corner_radius,
            }));
        }

        // Accent bar
        items.push(PositionedItem::Element(PdfElement::Rect {
            x, y, width: bar_width, height: total_height,
            fill_color: Some(color.clone()),
            stroke_color: None,
            stroke_width: 0.0,
            corner_radius,
        }));

        items.extend(content.items);

        LayoutResult { items, height: total_height }
    }

    fn layout_grid(
        &mut self,
        columns: &[GridColumnDef],
        cell_padding: &Padding,
        border_color: Option<&PdfColor>,
        rows: &[GridRow],
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        // Resolve column widths
        let mut fixed_total = 0.0f32;
        let mut weight_total = 0.0f32;
        for col in columns {
            match col {
                GridColumnDef::Fixed { width } => fixed_total += width,
                GridColumnDef::Weight { weight } => weight_total += weight,
            }
        }
        let flexible_space = (max_width - fixed_total).max(0.0);
        let col_widths: Vec<f32> = columns.iter().map(|col| match col {
            GridColumnDef::Fixed { width } => *width,
            GridColumnDef::Weight { weight } => {
                if weight_total > 0.0 { flexible_space * weight / weight_total } else { 0.0 }
            }
        }).collect();

        let mut items = Vec::new();
        let mut cur_y = y;

        for row in rows {
            // Layout all cells in this row
            let mut cell_results: Vec<LayoutResult> = Vec::new();
            let mut col_idx = 0;
            let mut cur_x = x;
            let mut max_row_height = 0.0f32;

            for cell in &row.cells {
                let cell_width: f32 = (col_idx..col_idx + cell.span as usize)
                    .filter_map(|i| col_widths.get(i))
                    .sum();
                let inner_w = (cell_width - cell_padding.left - cell_padding.right).max(0.0);
                let inner_x = cur_x + cell_padding.left;
                let inner_y = cur_y + cell_padding.top;

                let result = self.layout_nodes(&cell.children, inner_x, inner_y, inner_w);
                let cell_h = cell_padding.top + result.height + cell_padding.bottom;

                max_row_height = max_row_height.max(cell_h);
                cell_results.push(result);

                cur_x += cell_width;
                col_idx += cell.span as usize;
            }

            // Draw row background
            if let Some(bg) = &row.background {
                items.push(PositionedItem::Element(PdfElement::Rect {
                    x, y: cur_y, width: max_width, height: max_row_height,
                    fill_color: Some(bg.clone()),
                    stroke_color: None,
                    stroke_width: 0.0,
                    corner_radius: 0.0,
                }));
            }

            // Draw row bottom border
            if let Some(bc) = border_color {
                items.push(PositionedItem::Element(PdfElement::Line {
                    x1: x, y1: cur_y + max_row_height, x2: x + max_width, y2: cur_y + max_row_height,
                    color: bc.clone(), stroke_width: 1.0,
                    ripple: 0.0, thickness_ripple: 0.0,
                }));
            }

            // Add cell content
            for result in cell_results {
                items.extend(result.items);
            }

            cur_y += max_row_height;
        }

        LayoutResult { items, height: cur_y - y }
    }

    fn layout_stack(
        &mut self,
        children: &[DocumentNode],
        alignment: &VerticalAlignment,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> LayoutResult {
        // Two-pass: layout all children, then apply vertical alignment offsets.
        let mut child_results: Vec<(Vec<PositionedItem>, f32)> = Vec::new();
        let mut max_height = 0.0f32;
        for child in children {
            let result = self.layout_node(child, x, y, max_width);
            max_height = max_height.max(result.height);
            child_results.push((result.items, result.height));
        }

        let mut items = Vec::new();
        for (child_items, child_height) in child_results {
            let dy = match alignment {
                VerticalAlignment::Top => 0.0,
                VerticalAlignment::CenterVertically => (max_height - child_height) / 2.0,
                VerticalAlignment::Bottom => max_height - child_height,
            };
            if dy.abs() < 0.001 {
                items.extend(child_items);
            } else {
                for item in child_items {
                    items.push(offset_item(item, 0.0, dy));
                }
            }
        }
        LayoutResult { items, height: max_height }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

fn to_parley_align(align: &TextAlign) -> Alignment {
    match align {
        TextAlign::Left => Alignment::Start,
        TextAlign::Center => Alignment::Center,
        TextAlign::Right => Alignment::End,
    }
}

fn align_x(x: f32, max_width: f32, item_width: f32, align: &TextAlign) -> f32 {
    match align {
        TextAlign::Left => x,
        TextAlign::Center => x + (max_width - item_width) / 2.0,
        TextAlign::Right => x + max_width - item_width,
    }
}

/// Offset a PdfElement's coordinates by (dx, dy).
fn offset_element(elem: &PdfElement, dx: f32, dy: f32) -> PdfElement {
    match elem {
        PdfElement::Text { content, x, y, font_size, font, color } => PdfElement::Text {
            content: content.clone(), x: x + dx, y: y + dy,
            font_size: *font_size, font: font.clone(), color: color.clone(),
        },
        PdfElement::Rect { x, y, width, height, fill_color, stroke_color, stroke_width, corner_radius } => PdfElement::Rect {
            x: x + dx, y: y + dy, width: *width, height: *height,
            fill_color: fill_color.clone(), stroke_color: stroke_color.clone(),
            stroke_width: *stroke_width, corner_radius: *corner_radius,
        },
        PdfElement::Line { x1, y1, x2, y2, color, stroke_width, ripple, thickness_ripple } => PdfElement::Line {
            x1: x1 + dx, y1: y1 + dy, x2: x2 + dx, y2: y2 + dy,
            color: color.clone(), stroke_width: *stroke_width,
            ripple: *ripple, thickness_ripple: *thickness_ripple,
        },
        PdfElement::Image { x, y, width, height, data, format } => PdfElement::Image {
            x: x + dx, y: y + dy, width: *width, height: *height,
            data: data.clone(), format: format.clone(),
        },
        PdfElement::Sector { cx, cy, radius, start_angle, sweep_angle, fill_color, ripple, seed, mirror } => PdfElement::Sector {
            cx: cx + dx, cy: cy + dy, radius: *radius,
            start_angle: *start_angle, sweep_angle: *sweep_angle,
            fill_color: fill_color.clone(), ripple: *ripple, seed: *seed, mirror: *mirror,
        },
        PdfElement::Polygon { points, fill_color } => PdfElement::Polygon {
            points: points.iter().map(|p| crate::model::PdfPoint { x: p.x + dx, y: p.y + dy }).collect(),
            fill_color: fill_color.clone(),
        },
        PdfElement::Polyline { points, color, stroke_width, thickness_ripple } => PdfElement::Polyline {
            points: points.iter().map(|p| crate::model::PdfPoint { x: p.x + dx, y: p.y + dy }).collect(),
            color: color.clone(), stroke_width: *stroke_width, thickness_ripple: *thickness_ripple,
        },
        PdfElement::Svg { content, x, y, width, height } => PdfElement::Svg {
            content: content.clone(), x: x + dx, y: y + dy,
            width: *width, height: *height,
        },
        PdfElement::ClipStart { x, y, width, height, corner_radius } => PdfElement::ClipStart {
            x: x + dx, y: y + dy, width: *width, height: *height,
            corner_radius: *corner_radius,
        },
        PdfElement::ClipEnd {} => PdfElement::ClipEnd {},
    }
}

/// Offset a PositionedItem's coordinates.
fn offset_item(item: PositionedItem, dx: f32, dy: f32) -> PositionedItem {
    match item {
        PositionedItem::RichText { text, layout, x, y } => {
            PositionedItem::RichText { text, layout, x: x + dx, y: y + dy }
        }
        PositionedItem::Element(e) => PositionedItem::Element(offset_element(&e, dx, dy)),
    }
}
