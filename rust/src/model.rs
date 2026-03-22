use std::collections::HashMap;

use serde::Deserialize;

#[derive(Deserialize, Clone, Debug, PartialEq)]
pub struct PdfColor {
    pub r: f32,
    pub g: f32,
    pub b: f32,
}

impl Default for PdfColor {
    fn default() -> Self {
        PdfColor { r: 0.0, g: 0.0, b: 0.0 }
    }
}

/// Base64-encoded TTF/OTF font data.
#[derive(Deserialize)]
pub struct PdfFontDef {
    pub data: String,
}

fn default_font() -> String {
    String::new()
}

fn default_font_size() -> f32 {
    12.0
}

fn default_stroke_width() -> f32 {
    1.0
}

#[derive(Deserialize, Clone)]
#[serde(tag = "type")]
pub enum PdfElement {
    #[serde(rename = "text", rename_all = "camelCase")]
    Text {
        content: String,
        x: f32,
        y: f32,
        #[serde(default = "default_font_size")]
        font_size: f32,
        #[serde(default = "default_font")]
        font: String,
        #[serde(default)]
        color: PdfColor,
    },
    #[serde(rename = "rect", rename_all = "camelCase")]
    Rect {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        fill_color: Option<PdfColor>,
        stroke_color: Option<PdfColor>,
        #[serde(default = "default_stroke_width")]
        stroke_width: f32,
        #[serde(default)]
        corner_radius: f32,
    },
    #[serde(rename = "line", rename_all = "camelCase")]
    Line {
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        #[serde(default)]
        color: PdfColor,
        #[serde(default = "default_stroke_width")]
        stroke_width: f32,
        #[serde(default)]
        ripple: f32,
        #[serde(default)]
        thickness_ripple: f32,
    },
    #[serde(rename = "image", rename_all = "camelCase")]
    Image {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        data: String,
        #[serde(default = "default_image_format")]
        format: String,
    },
    #[serde(rename = "sector", rename_all = "camelCase")]
    Sector {
        cx: f32,
        cy: f32,
        radius: f32,
        start_angle: f32,
        sweep_angle: f32,
        fill_color: Option<PdfColor>,
        #[serde(default)]
        ripple: f32,
        #[serde(default)]
        seed: i32,
        #[serde(default)]
        mirror: bool,
    },
    #[serde(rename = "polygon", rename_all = "camelCase")]
    Polygon {
        points: Vec<PdfPoint>,
        fill_color: Option<PdfColor>,
    },
    #[serde(rename = "polyline", rename_all = "camelCase")]
    Polyline {
        points: Vec<PdfPoint>,
        #[serde(default)]
        color: PdfColor,
        #[serde(default = "default_stroke_width")]
        stroke_width: f32,
        #[serde(default)]
        thickness_ripple: f32,
    },
    #[serde(rename = "svg", rename_all = "camelCase")]
    Svg {
        content: String,
        x: f32,
        y: f32,
        width: f32,
        height: f32,
    },
    #[serde(rename = "clipStart", rename_all = "camelCase")]
    ClipStart {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        #[serde(default)]
        corner_radius: f32,
    },
    #[serde(rename = "clipEnd")]
    ClipEnd {},
}

#[derive(Deserialize, Clone)]
pub struct PdfPoint {
    pub x: f32,
    pub y: f32,
}

fn default_image_format() -> String {
    "png".to_string()
}

#[derive(Deserialize)]
pub struct PdfPage {
    #[serde(default = "default_page_width")]
    pub width: f32,
    #[serde(default = "default_page_height")]
    pub height: f32,
    #[serde(default)]
    pub elements: Vec<PdfElement>,
}

fn default_page_width() -> f32 {
    595.28
}
fn default_page_height() -> f32 {
    841.89
}

#[derive(Deserialize)]
pub struct PdfDocument {
    #[serde(default)]
    pub pages: Vec<PdfPage>,
    #[serde(default)]
    pub fonts: HashMap<String, PdfFontDef>,
}
