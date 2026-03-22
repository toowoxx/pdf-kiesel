use std::collections::HashMap;

use serde::Deserialize;

use crate::model::{PdfColor, PdfElement, PdfFontDef};

// ── Primitives ──────────────────────────────────────────────────────────────

fn default_font_size() -> f32 {
    12.0
}
fn default_font() -> String {
    String::new()
}
fn default_line_spacing() -> f32 {
    1.4
}
fn default_stroke_width() -> f32 {
    1.0
}
fn default_page_width() -> f32 {
    595.28
}
fn default_page_height() -> f32 {
    841.89
}
fn default_image_format() -> String {
    "png".to_string()
}
fn default_bullet_indent() -> f32 {
    14.0
}
fn default_item_gap() -> f32 {
    2.0
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Margin {
    #[serde(default = "default_margin")]
    pub top: f32,
    #[serde(default = "default_margin")]
    pub bottom: f32,
    #[serde(default = "default_margin")]
    pub left: f32,
    #[serde(default = "default_margin")]
    pub right: f32,
}

fn default_margin() -> f32 {
    50.0
}

impl Default for Margin {
    fn default() -> Self {
        Margin {
            top: default_margin(),
            bottom: default_margin(),
            left: default_margin(),
            right: default_margin(),
        }
    }
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Padding {
    #[serde(default)]
    pub top: f32,
    #[serde(default)]
    pub right: f32,
    #[serde(default)]
    pub bottom: f32,
    #[serde(default)]
    pub left: f32,
}

impl Default for Padding {
    fn default() -> Self {
        Padding {
            top: 0.0,
            right: 0.0,
            bottom: 0.0,
            left: 0.0,
        }
    }
}

// ── Rich text ───────────────────────────────────────────────────────────────

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RichSegment {
    pub text: String,
    #[serde(default)]
    pub bold: bool,
}

// ── Enums ───────────────────────────────────────────────────────────────────

#[derive(Deserialize, Clone, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TextAlign {
    #[default]
    Left,
    Center,
    Right,
}

#[derive(Deserialize, Clone, Default)]
pub enum HorizontalAlignment {
    #[default]
    Start,
    CenterHorizontally,
    End,
}

#[derive(Deserialize, Clone, Default)]
pub enum VerticalAlignment {
    #[default]
    Top,
    CenterVertically,
    Bottom,
}

#[derive(Deserialize, Clone, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SplitStrategy {
    #[default]
    None,
    SplitNearestView,
    SplitCenter,
    SplitAnywhere,
}

// ── Grid column definitions ─────────────────────────────────────────────────

#[derive(Deserialize, Clone)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum GridColumnDef {
    #[serde(rename = "fixed")]
    Fixed { width: f32 },
    #[serde(rename = "weight")]
    Weight {
        #[serde(default = "default_weight")]
        weight: f32,
    },
}

fn default_weight() -> f32 {
    1.0
}

// ── Row / Grid cells ────────────────────────────────────────────────────────

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RowCell {
    #[serde(default)]
    pub weight: Option<f32>,
    #[serde(default)]
    pub fixed_width: Option<f32>,
    #[serde(default)]
    pub children: Vec<DocumentNode>,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GridRow {
    #[serde(default)]
    pub background: Option<PdfColor>,
    #[serde(default)]
    pub cells: Vec<GridCell>,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GridCell {
    #[serde(default = "default_span")]
    pub span: u32,
    #[serde(default)]
    pub children: Vec<DocumentNode>,
}

fn default_span() -> u32 {
    1
}

// ── Document node tree ──────────────────────────────────────────────────────

#[derive(Deserialize, Clone)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum DocumentNode {
    // ── Leaf: text ──────────────────────────────────────────────────────
    #[serde(rename = "text", rename_all = "camelCase")]
    Text {
        content: String,
        #[serde(default = "default_font_size")]
        font_size: f32,
        #[serde(default = "default_font")]
        font: String,
        #[serde(default)]
        color: PdfColor,
        #[serde(default)]
        align: TextAlign,
        #[serde(default)]
        bold: bool,
        #[serde(default)]
        italic: bool,
        #[serde(default = "default_line_spacing")]
        line_spacing: f32,
    },

    #[serde(rename = "paragraph", rename_all = "camelCase")]
    Paragraph {
        content: String,
        #[serde(default = "default_font_size")]
        font_size: f32,
        #[serde(default = "default_font")]
        font: String,
        #[serde(default)]
        color: PdfColor,
        #[serde(default)]
        align: TextAlign,
        #[serde(default = "default_line_spacing")]
        line_spacing: f32,
        #[serde(default)]
        bold: bool,
        #[serde(default)]
        markdown: bool,
    },

    #[serde(rename = "richParagraph", rename_all = "camelCase")]
    RichParagraph {
        segments: Vec<RichSegment>,
        #[serde(default = "default_font_size")]
        font_size: f32,
        #[serde(default = "default_font")]
        font: String,
        #[serde(default)]
        color: PdfColor,
        #[serde(default = "default_line_spacing")]
        line_spacing: f32,
    },

    // ── Leaf: lists ─────────────────────────────────────────────────────
    #[serde(rename = "bulletList", rename_all = "camelCase")]
    BulletList {
        items: Vec<String>,
        #[serde(default)]
        bullet_color: PdfColor,
        #[serde(default = "default_font_size")]
        font_size: f32,
        #[serde(default = "default_font")]
        font: String,
        #[serde(default)]
        color: PdfColor,
        #[serde(default = "default_line_spacing")]
        line_spacing: f32,
        #[serde(default)]
        split_strategy: SplitStrategy,
        #[serde(default = "default_bullet_indent")]
        bullet_indent: f32,
        #[serde(default = "default_item_gap")]
        item_gap: f32,
        #[serde(default)]
        markdown: bool,
    },

    #[serde(rename = "richBulletList", rename_all = "camelCase")]
    RichBulletList {
        items: Vec<Vec<RichSegment>>,
        #[serde(default)]
        bullet_color: PdfColor,
        #[serde(default = "default_font_size")]
        font_size: f32,
        #[serde(default = "default_font")]
        font: String,
        #[serde(default)]
        color: PdfColor,
        #[serde(default = "default_line_spacing")]
        line_spacing: f32,
        #[serde(default)]
        split_strategy: SplitStrategy,
        #[serde(default = "default_bullet_indent")]
        bullet_indent: f32,
        #[serde(default = "default_item_gap")]
        item_gap: f32,
    },

    // ── Leaf: structural ────────────────────────────────────────────────
    #[serde(rename = "spacer")]
    Spacer { height: f32 },

    #[serde(rename = "divider", rename_all = "camelCase")]
    Divider {
        #[serde(default)]
        color: PdfColor,
        #[serde(default = "default_divider_stroke")]
        stroke_width: f32,
    },

    #[serde(rename = "rect", rename_all = "camelCase")]
    Rect {
        #[serde(default)]
        width: Option<f32>,
        height: f32,
        #[serde(default)]
        fill_color: Option<PdfColor>,
        #[serde(default)]
        stroke_color: Option<PdfColor>,
        #[serde(default = "default_stroke_width")]
        stroke_width: f32,
        #[serde(default)]
        corner_radius: f32,
    },

    // ── Leaf: media ─────────────────────────────────────────────────────
    #[serde(rename = "image", rename_all = "camelCase")]
    Image {
        data: String,
        width: f32,
        height: f32,
        #[serde(default)]
        align: TextAlign,
        #[serde(default = "default_image_format")]
        format: String,
    },

    #[serde(rename = "svg", rename_all = "camelCase")]
    Svg {
        content: String,
        width: f32,
        height: f32,
        #[serde(default)]
        align: TextAlign,
    },

    // ── Container: layout ───────────────────────────────────────────────
    #[serde(rename = "column", rename_all = "camelCase")]
    Column {
        #[serde(default)]
        gap: f32,
        #[serde(default)]
        alignment: HorizontalAlignment,
        #[serde(default)]
        split_strategy: SplitStrategy,
        #[serde(default)]
        children: Vec<DocumentNode>,
    },

    #[serde(rename = "row", rename_all = "camelCase")]
    Row {
        #[serde(default)]
        gap: f32,
        #[serde(default)]
        alignment: VerticalAlignment,
        cells: Vec<RowCell>,
    },

    #[serde(rename = "padded", rename_all = "camelCase")]
    Padded {
        #[serde(default)]
        padding: Padding,
        #[serde(default)]
        background: Option<PdfColor>,
        #[serde(default)]
        corner_radius: f32,
        #[serde(default)]
        children: Vec<DocumentNode>,
    },

    #[serde(rename = "accentBar", rename_all = "camelCase")]
    AccentBar {
        color: PdfColor,
        #[serde(default = "default_bar_width")]
        bar_width: f32,
        #[serde(default)]
        background: Option<PdfColor>,
        #[serde(default)]
        corner_radius: f32,
        #[serde(default)]
        padding: f32,
        #[serde(default)]
        children: Vec<DocumentNode>,
    },

    #[serde(rename = "grid", rename_all = "camelCase")]
    Grid {
        columns: Vec<GridColumnDef>,
        #[serde(default)]
        cell_padding: Padding,
        #[serde(default)]
        border_color: Option<PdfColor>,
        #[serde(default)]
        rows: Vec<GridRow>,
    },

    #[serde(rename = "stack", rename_all = "camelCase")]
    Stack {
        #[serde(default)]
        alignment: VerticalAlignment,
        #[serde(default)]
        children: Vec<DocumentNode>,
    },

    /// Pre-positioned flat elements (for charts, custom drawings).
    #[serde(rename = "canvas", rename_all = "camelCase")]
    Canvas {
        height: f32,
        #[serde(default)]
        elements: Vec<PdfElement>,
    },

    /// Zero-height overlay of pre-positioned elements.
    #[serde(rename = "overlay", rename_all = "camelCase")]
    Overlay {
        #[serde(default)]
        elements: Vec<PdfElement>,
    },
}

fn default_bar_width() -> f32 {
    3.0
}
fn default_divider_stroke() -> f32 {
    0.5
}

// ── Top-level document ──────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TreePage {
    #[serde(default = "default_page_width")]
    pub width: f32,
    #[serde(default = "default_page_height")]
    pub height: f32,
    #[serde(default)]
    pub margin: Margin,
    #[serde(default)]
    pub background: Option<PdfColor>,
    #[serde(default)]
    pub split_strategy: SplitStrategy,
    #[serde(default)]
    pub content: Vec<DocumentNode>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TreeDocument {
    #[serde(default)]
    pub fonts: HashMap<String, PdfFontDef>,
    #[serde(default)]
    pub pages: Vec<TreePage>,
}
