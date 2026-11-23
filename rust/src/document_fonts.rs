// SPDX-License-Identifier: Apache-2.0

use std::collections::HashMap;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use krilla::text::{Font, Tag};
use parley::fontique::Blob;

use crate::model::{FontWeight, PdfFontDef};

/// Why a document cannot be drawn in its fonts: the operation on its fonts that failed, and the
/// font it failed on.
#[derive(Debug)]
pub enum DocumentFontUnusable {
    /// Decoding the base64 data of the font failed.
    DecodeFontData { font: String, source: base64::DecodeError },
    /// The default font named by the document is not among its registered fonts.
    FindDefaultFont { font: String },
    /// The document registers fonts but sets no default font for text without a font.
    ChooseDefaultFont,
    /// Canvas text is in a font that is not registered, and the document registers no font.
    FindCanvasTextFont { font: String },
    /// krilla could not read the font that canvas text is drawn in.
    ReadCanvasTextFont { font: String },
}

impl std::fmt::Display for DocumentFontUnusable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::DecodeFontData { font, .. } => write!(f, "the data of the font {font} is not base64"),
            Self::FindDefaultFont { font } => write!(f, "the default font {font} is not registered"),
            Self::ChooseDefaultFont => write!(f, "the document registers fonts but sets no default font"),
            Self::FindCanvasTextFont { font } => {
                write!(f, "canvas text in the font \"{font}\" has no registered font and no default font")
            }
            Self::ReadCanvasTextFont { font } => write!(f, "the font {font} of canvas text cannot be read"),
        }
    }
}

impl std::error::Error for DocumentFontUnusable {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::DecodeFontData { source, .. } => Some(source),
            Self::FindDefaultFont { .. }
            | Self::ChooseDefaultFont
            | Self::FindCanvasTextFont { .. }
            | Self::ReadCanvasTextFont { .. } => None,
        }
    }
}

/// One krilla font: the font file, the index of the font in it, and the values of its variation
/// axes as bits.
#[derive(PartialEq, Eq, Hash)]
struct FontInstanceKey {
    file_id: u64,
    index: u32,
    coords: Vec<(Tag, u32)>,
}

/// The fonts of one document: the file of each registered font, the default font for text
/// without a font, and the krilla font of each file at each variation the document draws it at.
/// Each of those krilla fonts is created once per document.
pub struct DocumentFonts {
    files: HashMap<String, Blob<u8>>,
    default_font: Option<String>,
    instances: HashMap<FontInstanceKey, Option<Font>>,
}

impl DocumentFonts {
    pub fn read(
        fonts: &HashMap<String, PdfFontDef>,
        default_font: Option<&str>,
    ) -> Result<Self, DocumentFontUnusable> {
        let files = fonts
            .iter()
            .map(|(name, def)| {
                let data = BASE64
                    .decode(&def.data)
                    .map_err(|source| DocumentFontUnusable::DecodeFontData { font: name.clone(), source })?;
                Ok((name.clone(), Blob::from(data)))
            })
            .collect::<Result<HashMap<_, _>, _>>()?;
        match default_font {
            Some(font) if !files.contains_key(font) => Err(DocumentFontUnusable::FindDefaultFont { font: font.to_owned() }),
            None if !files.is_empty() => Err(DocumentFontUnusable::ChooseDefaultFont),
            _ => Ok(Self { files, default_font: default_font.map(str::to_owned), instances: HashMap::new() }),
        }
    }

    pub fn files(&self) -> &HashMap<String, Blob<u8>> {
        &self.files
    }

    /// The font canvas text in `font` is drawn with, at `weight`. Text in a font that is not
    /// registered, an empty name included, is drawn in the default font.
    pub fn canvas_font(&mut self, font: &str, weight: FontWeight) -> Result<Font, DocumentFontUnusable> {
        let (name, file) = self
            .files
            .get_key_value(font)
            .or_else(|| self.default_font.as_ref().and_then(|default| self.files.get_key_value(default)))
            .map(|(name, file)| (name.clone(), file.clone()))
            .ok_or_else(|| DocumentFontUnusable::FindCanvasTextFont { font: font.to_owned() })?;
        self.instance(&file, 0, &[(Tag::new(b"wght"), weight.value())])
            .ok_or(DocumentFontUnusable::ReadCanvasTextFont { font: name })
    }

    /// The krilla font of `file` at the values `coords` of its variation axes. A font without
    /// one of those axes draws as if that value were not given.
    pub fn instance(&mut self, file: &Blob<u8>, index: u32, coords: &[(Tag, f32)]) -> Option<Font> {
        let key = FontInstanceKey {
            file_id: file.id(),
            index,
            coords: coords.iter().map(|&(tag, value)| (tag, value.to_bits())).collect(),
        };
        self.instances
            .entry(key)
            .or_insert_with(|| {
                let (data, _id_is_in_the_key) = file.clone().into_raw_parts();
                Font::new_variable(data.into(), index, coords)
            })
            .clone()
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use base64::engine::general_purpose::STANDARD as BASE64;
    use base64::Engine;
    use write_fonts::tables::fvar::{AxisInstanceArrays, Fvar, VariationAxisRecord};
    use write_fonts::tables::head::{Flags, Head, MacStyle};
    use write_fonts::tables::maxp::Maxp;
    use write_fonts::types::{Fixed, LongDateTime, NameId, Tag};
    use write_fonts::FontBuilder;

    use super::{DocumentFontUnusable, DocumentFonts};
    use crate::model::{FontWeight, PdfFontDef};

    /// A font with a weight axis from 100 to 900 and only the glyph `.notdef`. Fonts with another
    /// `units_per_em` are different fonts.
    fn variable_font(units_per_em: u16) -> String {
        let size = i16::try_from(units_per_em).expect("the em square fits the bounding box");
        let head = Head::new(
            Fixed::ONE, 0, Flags::empty(), units_per_em, LongDateTime::new(0), LongDateTime::new(0),
            0, 0, size, size, MacStyle::empty(), 8, 0,
        );
        let weight_axis = VariationAxisRecord::new(
            Tag::new(b"wght"), Fixed::from_f64(100.0), Fixed::from_f64(400.0), Fixed::from_f64(900.0), 0, NameId::new(256),
        );
        let font = FontBuilder::new()
            .add_table(&head)
            .and_then(|font| font.add_table(&Maxp::new(1)))
            .and_then(|font| font.add_table(&Fvar::new(AxisInstanceArrays::new(vec![weight_axis], vec![]))))
            .expect("the tables are valid")
            .build();
        BASE64.encode(font)
    }

    fn document_fonts(names: &[&str]) -> HashMap<String, PdfFontDef> {
        (1000..).zip(names).map(|(units_per_em, name)| (name.to_string(), PdfFontDef { data: variable_font(units_per_em) })).collect()
    }

    fn weight(value: f32) -> FontWeight {
        FontWeight::try_from(value).expect("the weight is on the CSS scale")
    }

    #[test]
    fn canvas_text_without_a_font_is_drawn_in_the_default_font() {
        let names = ["RobotoMono", "Inter", "Outfit", "Alpha", "Zeta"];
        // Each map orders its keys by a seed of its own, so the order differs from map to map.
        for _ in 0..20 {
            let mut fonts = DocumentFonts::read(&document_fonts(&names), Some("Outfit")).expect("the fonts are usable");

            let drawn = fonts.canvas_font("", FontWeight::NORMAL).expect("the default font is usable");

            assert_eq!(Some(drawn), fonts.canvas_font("Outfit", FontWeight::NORMAL).ok());
        }
    }

    #[test]
    fn canvas_text_in_a_font_that_is_not_registered_is_drawn_in_the_default_font() {
        let mut fonts = DocumentFonts::read(&document_fonts(&["Inter", "Outfit"]), Some("Inter")).expect("the fonts are usable");

        let drawn = fonts.canvas_font("Helvetica", FontWeight::NORMAL).expect("the default font is usable");

        assert_eq!(Some(drawn), fonts.canvas_font("Inter", FontWeight::NORMAL).ok());
    }

    #[test]
    fn canvas_text_is_drawn_at_its_weight() {
        let mut fonts = DocumentFonts::read(&document_fonts(&["Inter"]), Some("Inter")).expect("the fonts are usable");

        let semibold = fonts.canvas_font("Inter", weight(600.0)).expect("the font is usable");
        let bold = fonts.canvas_font("Inter", FontWeight::BOLD).expect("the font is usable");

        assert_ne!(semibold, bold);
        assert_eq!(Some(semibold), fonts.canvas_font("Inter", weight(600.0)).ok());
    }

    #[test]
    fn fonts_without_a_default_font_fail() {
        let read = DocumentFonts::read(&document_fonts(&["Inter"]), None);

        assert!(matches!(read, Err(DocumentFontUnusable::ChooseDefaultFont)));
    }

    #[test]
    fn canvas_text_fails_when_the_document_registers_no_font() {
        let mut fonts = DocumentFonts::read(&HashMap::new(), None).expect("a document without fonts is usable");

        let drawn = fonts.canvas_font("", FontWeight::NORMAL);

        assert!(matches!(drawn, Err(DocumentFontUnusable::FindCanvasTextFont { .. })));
    }

    #[test]
    fn default_font_that_is_not_registered_fails() {
        let read = DocumentFonts::read(&document_fonts(&["Inter"]), Some("Intre"));

        assert!(matches!(read, Err(DocumentFontUnusable::FindDefaultFont { font }) if font == "Intre"));
    }

    #[test]
    fn font_data_that_is_not_base64_fails() {
        let fonts = HashMap::from([("Inter".to_string(), PdfFontDef { data: "not base64!".to_string() })]);

        let read = DocumentFonts::read(&fonts, Some("Inter"));

        assert!(matches!(read, Err(DocumentFontUnusable::DecodeFontData { font, .. }) if font == "Inter"));
    }
}
