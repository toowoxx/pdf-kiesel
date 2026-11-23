// SPDX-License-Identifier: Apache-2.0

use skrifa::MetadataProvider;
use skrifa::raw::types::NameId;

/// Read the font family name from OpenType/TrueType font data.
///
/// Fontique files a font under its typographic family (name ID 16) when the font has one, so this
/// reads that entry first and the legacy family (name ID 1) otherwise. Variable fonts often set
/// the legacy family to one instance, such as "Outfit Thin".
pub(crate) fn read_family_name(data: &[u8]) -> Option<String> {
    let font = skrifa::FontRef::from_index(data, 0).ok()?;
    [NameId::TYPOGRAPHIC_FAMILY_NAME, NameId::FAMILY_NAME]
        .into_iter()
        .find_map(|id| font.localized_strings(id).english_or_first())
        .map(|s| s.chars().collect())
}

#[cfg(test)]
mod tests {
    use super::read_family_name;
    use write_fonts::tables::name::{Name, NameRecord};
    use write_fonts::types::NameId;
    use write_fonts::FontBuilder;

    // Platform, encoding and language IDs of the OpenType name table, from
    // https://learn.microsoft.com/en-us/typography/opentype/spec/name (checked 2026-11-18).
    const WINDOWS_PLATFORM: u16 = 3;
    const UNICODE_BMP_ENCODING: u16 = 1;
    const ENGLISH_US: u16 = 0x409;

    fn font_with_names(names: &[(NameId, &str)]) -> Vec<u8> {
        let records = names
            .iter()
            .map(|&(id, text)| {
                NameRecord::new(WINDOWS_PLATFORM, UNICODE_BMP_ENCODING, ENGLISH_US, id, text.to_string().into())
            })
            .collect();
        FontBuilder::new()
            .add_table(&Name::new(records))
            .expect("the name table is valid")
            .build()
    }

    #[test]
    fn typographic_family_wins_over_a_legacy_family_set_to_one_instance() {
        let font = font_with_names(&[
            (NameId::FAMILY_NAME, "Outfit Thin"),
            (NameId::TYPOGRAPHIC_FAMILY_NAME, "Outfit"),
        ]);

        assert_eq!(read_family_name(&font).as_deref(), Some("Outfit"));
    }

    #[test]
    fn legacy_family_is_read_when_typographic_family_is_absent() {
        let font = font_with_names(&[(NameId::FAMILY_NAME, "Inter")]);

        assert_eq!(read_family_name(&font).as_deref(), Some("Inter"));
    }
}
