//! Page numbers in a running footer.
//!
//! A document that runs over several pages tells the reader where they are ("Seite 2 von 5").
//! The number of pages is known only after the whole document is paginated, so the footer is
//! written with the placeholders [`PAGE`] and [`PAGES`] and filled in for each page just before
//! that page is drawn.

use crate::model::PdfElement;
use crate::tree::{DocumentNode, GridCell, GridRow, RowCell};

/// Stands for the number of the page the footer is drawn on, counted from 1.
pub const PAGE: &str = "{page}";
/// Stands for the number of pages of the whole document.
pub const PAGES: &str = "{pages}";

/// A copy of `nodes` with every placeholder in their text replaced by the numbers of this page.
pub fn number_pages(nodes: &[DocumentNode], page: usize, pages: usize) -> Vec<DocumentNode> {
    let numbers = PageNumbers { page: page.to_string(), pages: pages.to_string() };
    nodes.iter().map(|node| numbers.node(node)).collect()
}

struct PageNumbers {
    page: String,
    pages: String,
}

impl PageNumbers {
    fn text(&self, content: &str) -> String {
        content.replace(PAGES, &self.pages).replace(PAGE, &self.page)
    }

    fn nodes(&self, nodes: &[DocumentNode]) -> Vec<DocumentNode> {
        nodes.iter().map(|node| self.node(node)).collect()
    }

    fn elements(&self, elements: &[PdfElement]) -> Vec<PdfElement> {
        elements
            .iter()
            .map(|element| match element {
                PdfElement::Text { content, x, y, font_size, font, color, weight } => PdfElement::Text {
                    content: self.text(content),
                    x: *x,
                    y: *y,
                    font_size: *font_size,
                    font: font.clone(),
                    color: color.clone(),
                    weight: *weight,
                },
                other => other.clone(),
            })
            .collect()
    }

    fn node(&self, node: &DocumentNode) -> DocumentNode {
        let mut node = node.clone();
        match &mut node {
            DocumentNode::Text { content, .. } | DocumentNode::Paragraph { content, .. } => {
                *content = self.text(content);
            }
            DocumentNode::RichParagraph { segments, .. } => {
                segments.iter_mut().for_each(|segment| segment.text = self.text(&segment.text));
            }
            DocumentNode::BulletList { items, .. } => {
                items.iter_mut().for_each(|item| *item = self.text(item));
            }
            DocumentNode::RichBulletList { items, .. } => {
                items.iter_mut().flatten().for_each(|segment| segment.text = self.text(&segment.text));
            }
            DocumentNode::Column { children, .. }
            | DocumentNode::Padded { children, .. }
            | DocumentNode::AccentBar { children, .. }
            | DocumentNode::Stack { children, .. } => {
                *children = self.nodes(children);
            }
            DocumentNode::Row { cells, .. } => {
                *cells = cells
                    .iter()
                    .map(|cell| RowCell { children: self.nodes(&cell.children), ..cell.clone() })
                    .collect();
            }
            DocumentNode::Grid { rows, .. } => {
                *rows = rows
                    .iter()
                    .map(|row| GridRow {
                        cells: row
                            .cells
                            .iter()
                            .map(|cell| GridCell { children: self.nodes(&cell.children), ..cell.clone() })
                            .collect(),
                        ..row.clone()
                    })
                    .collect();
            }
            DocumentNode::Canvas { elements, .. } | DocumentNode::Overlay { elements } => {
                *elements = self.elements(elements);
            }
            DocumentNode::Spacer { .. }
            | DocumentNode::Divider { .. }
            | DocumentNode::Rect { .. }
            | DocumentNode::Image { .. }
            | DocumentNode::Svg { .. } => {}
        }
        node
    }
}

#[cfg(test)]
mod tests {
    use super::number_pages;
    use crate::tree::DocumentNode;

    fn footer(json: &str) -> Vec<DocumentNode> {
        serde_json::from_str(json).expect("the footer is valid")
    }

    fn texts(nodes: &[DocumentNode]) -> Vec<String> {
        let mut found = Vec::new();
        for node in nodes {
            match node {
                DocumentNode::Text { content, .. } | DocumentNode::Paragraph { content, .. } => found.push(content.clone()),
                DocumentNode::Row { cells, .. } => cells.iter().for_each(|cell| found.extend(texts(&cell.children))),
                DocumentNode::Column { children, .. } => found.extend(texts(children)),
                DocumentNode::Canvas { elements, .. } => elements.iter().for_each(|element| {
                    if let crate::model::PdfElement::Text { content, .. } = element {
                        found.push(content.clone());
                    }
                }),
                _ => {}
            }
        }
        found
    }

    #[test]
    fn fills_in_the_page_and_the_number_of_pages() {
        let nodes = footer(r#"[{"type":"text","content":"Seite {page} von {pages}","weight":400}]"#);

        assert_eq!(texts(&number_pages(&nodes, 2, 5)), vec!["Seite 2 von 5"]);
    }

    #[test]
    fn fills_in_text_nested_in_rows_columns_and_canvases() {
        let nodes = footer(
            r#"[{"type":"row","cells":[
                {"weight":1,"children":[{"type":"text","content":"Auswertung","weight":400}]},
                {"weight":1,"children":[{"type":"column","children":[
                    {"type":"paragraph","content":"{page}/{pages}","weight":400}]}]}]},
              {"type":"canvas","height":10,"elements":[
                {"type":"text","content":"S. {page}","x":0,"y":0,"weight":400}]}]"#,
        );

        assert_eq!(texts(&number_pages(&nodes, 3, 3)), vec!["Auswertung", "3/3", "S. 3"]);
    }

    #[test]
    fn leaves_text_without_placeholders_as_it_is() {
        let nodes = footer(r#"[{"type":"text","content":"{seite} {Page}","weight":400}]"#);

        assert_eq!(texts(&number_pages(&nodes, 1, 1)), vec!["{seite} {Page}"]);
    }
}
