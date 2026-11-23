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

/// The weight text is drawn at, on the CSS scale: 400 is normal, 700 is bold. A variable font
/// with a weight axis draws exactly this weight. The range and the named weights are those of
/// `font-weight` in CSS Fonts Level 4, https://www.w3.org/TR/css-fonts-4/#font-weight-prop.
#[derive(Deserialize, Clone, Copy, Debug, PartialEq)]
#[serde(try_from = "f32")]
pub struct FontWeight(f32);

impl FontWeight {
    pub const NORMAL: FontWeight = FontWeight(400.0);
    pub const BOLD: FontWeight = FontWeight(700.0);

    pub fn value(self) -> f32 {
        self.0
    }
}

/// A weight outside the range from 1 to 1000 that CSS allows.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FontWeightOutOfRange(pub f32);

impl std::fmt::Display for FontWeightOutOfRange {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "the font weight {} is not between 1 and 1000", self.0)
    }
}

impl std::error::Error for FontWeightOutOfRange {}

impl TryFrom<f32> for FontWeight {
    type Error = FontWeightOutOfRange;

    fn try_from(value: f32) -> Result<Self, Self::Error> {
        if (1.0..=1000.0).contains(&value) {
            Ok(FontWeight(value))
        } else {
            Err(FontWeightOutOfRange(value))
        }
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
        weight: FontWeight,
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
