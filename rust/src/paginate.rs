use crate::layout::{LayoutEngine, LayoutResult, PositionedItem};
use crate::tree::{DocumentNode, SplitStrategy};

/// Measured child: height + index into original children vec.
struct MeasuredChild {
    index: usize,
    top_offset: f32,
    height: f32,
}

/// Paginate a list of children into multiple pages.
/// Returns one `LayoutResult` per output page.
pub fn paginate(
    engine: &mut LayoutEngine,
    children: &[DocumentNode],
    x: f32,
    start_y: f32,
    max_width: f32,
    gap: f32,
    page_content_height: f32,
    strategy: &SplitStrategy,
) -> Vec<LayoutResult> {
    // Measure all children
    let measured = measure_children(engine, children, x, start_y, max_width, gap);
    if measured.is_empty() {
        return vec![LayoutResult { items: vec![], height: 0.0 }];
    }

    let last = measured.last().expect("measured is non-empty (checked above)");
    let total_height = last.top_offset + last.height;
    if total_height <= page_content_height + 0.1 {
        // Everything fits on one page — layout once at start_y
        let result = engine.layout_nodes(children, x, start_y, max_width);
        return vec![result];
    }

    distribute(engine, children, &measured, x, start_y, max_width, gap, page_content_height, strategy)
}

fn measure_children(
    engine: &mut LayoutEngine,
    children: &[DocumentNode],
    x: f32,
    start_y: f32,
    max_width: f32,
    gap: f32,
) -> Vec<MeasuredChild> {
    let mut measured = Vec::new();
    let mut offset = 0.0f32;
    for (i, child) in children.iter().enumerate() {
        if i > 0 && gap > 0.0 {
            offset += gap;
        }
        let result = engine.layout_node(child, x, start_y + offset, max_width);
        measured.push(MeasuredChild {
            index: i,
            top_offset: offset,
            height: result.height,
        });
        offset += result.height;
    }
    measured
}

fn distribute(
    engine: &mut LayoutEngine,
    children: &[DocumentNode],
    measured: &[MeasuredChild],
    x: f32,
    start_y: f32,
    max_width: f32,
    _gap: f32,
    page_content_height: f32,
    strategy: &SplitStrategy,
) -> Vec<LayoutResult> {
    let mut pages: Vec<LayoutResult> = Vec::new();
    let mut page_items: Vec<PositionedItem> = Vec::new();
    let mut page_top_offset = 0.0f32;
    let mut page_height = 0.0f32;

    let mut i = 0;
    while i < measured.len() {
        let child = &measured[i];
        let pos_on_page = child.top_offset - page_top_offset;
        let end_on_page = pos_on_page + child.height;

        if end_on_page <= page_content_height + 0.1 {
            // Fits on current page — layout at page-relative position
            let y = start_y + pos_on_page;
            let result = engine.layout_node(&children[child.index], x, y, max_width);
            page_items.extend(result.items);
            page_height = end_on_page;
            i += 1;
            continue;
        }

        let effective_strategy = child_split_strategy(&children[child.index], strategy);

        match effective_strategy {
            SplitStrategy::None => {
                // Don't split — force onto current page even if it overflows
                let y = start_y + pos_on_page;
                let result = engine.layout_node(&children[child.index], x, y, max_width);
                page_items.extend(result.items);
                page_height = end_on_page;
                i += 1;
            }

            SplitStrategy::SplitNearestView => {
                if pos_on_page > 0.1 {
                    // Start a new page at this child
                    if !page_items.is_empty() {
                        pages.push(LayoutResult { items: page_items, height: page_height });
                        page_items = Vec::new();
                    }
                    page_top_offset = child.top_offset;
                    page_height = 0.0;
                    // Don't increment i — re-evaluate this child on the new page
                } else {
                    // This child is at the top of a page and still doesn't fit.
                    // Try to split its sub-children recursively.
                    if let Some((sub_children, sub_gap)) = splittable_children(&children[child.index]) {
                        let sub_pages = paginate(
                            engine, sub_children, x, start_y, max_width,
                            sub_gap, page_content_height, &effective_strategy,
                        );
                        pages.extend(sub_pages);
                        i += 1;
                        page_items = Vec::new();
                        page_top_offset = if i < measured.len() { measured[i].top_offset } else { 0.0 };
                        page_height = 0.0;
                    } else {
                        // Not splittable — force it
                        let y = start_y + pos_on_page;
                        let result = engine.layout_node(&children[child.index], x, y, max_width);
                        page_items.extend(result.items);
                        page_height = end_on_page;
                        i += 1;
                    }
                }
            }

            SplitStrategy::SplitCenter | SplitStrategy::SplitAnywhere => {
                // For now, treat both as split-nearest-view since we don't have
                // element-level clipping in the new tree model.
                // The split happens at child boundaries.
                if pos_on_page > 0.1 {
                    if !page_items.is_empty() {
                        pages.push(LayoutResult { items: page_items, height: page_height });
                        page_items = Vec::new();
                    }
                    page_top_offset = child.top_offset;
                    page_height = 0.0;
                } else {
                    let y = start_y + pos_on_page;
                    let result = engine.layout_node(&children[child.index], x, y, max_width);
                    page_items.extend(result.items);
                    page_height = end_on_page;
                    i += 1;
                }
            }
        }
    }

    if !page_items.is_empty() {
        pages.push(LayoutResult { items: page_items, height: page_height });
    }

    pages
}

fn child_split_strategy<'a>(node: &'a DocumentNode, parent: &'a SplitStrategy) -> SplitStrategy {
    match node {
        DocumentNode::Column { split_strategy, .. } if !matches!(split_strategy, SplitStrategy::None) => {
            split_strategy.clone()
        }
        DocumentNode::BulletList { split_strategy, .. } if !matches!(split_strategy, SplitStrategy::None) => {
            split_strategy.clone()
        }
        DocumentNode::RichBulletList { split_strategy, .. } if !matches!(split_strategy, SplitStrategy::None) => {
            split_strategy.clone()
        }
        _ => parent.clone(),
    }
}

fn splittable_children(node: &DocumentNode) -> Option<(&[DocumentNode], f32)> {
    match node {
        DocumentNode::Column { children, gap, .. } => Some((children.as_slice(), *gap)),
        // Bullet lists aren't directly splittable at the DocumentNode level since
        // each item is a String, not a DocumentNode. The layout engine handles them
        // as a single unit. For future improvement, the Kotlin side could wrap each
        // bullet item as a separate Column child.
        _ => None,
    }
}
