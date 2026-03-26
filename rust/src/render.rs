use std::collections::HashMap;
use std::sync::Arc;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use krilla::color::rgb;
use krilla::geom::{PathBuilder, Point, Rect, Size, Transform};
use krilla::num::NormalizedF32;
use krilla::page::PageSettings;
use krilla::paint::{Fill, FillRule, LineCap, LineJoin, Stroke};
use krilla::text::{Font, GlyphId, KrillaGlyph, Tag, TextDirection};
use skrifa::MetadataProvider;
use krilla::Document;
use krilla_svg::{SvgSettings, SurfaceExt};
use parley::{FontContext, LayoutContext};

use crate::layout::{LayoutEngine, PositionedItem, register_fonts};
use crate::model::{PdfColor, PdfDocument, PdfElement};
use crate::paginate::paginate;
use crate::tree::TreeDocument;

pub fn render(doc: &PdfDocument) -> Vec<u8> {
    let mut document = Document::new();

    // Decode and load custom fonts
    let mut fonts: HashMap<String, Font> = HashMap::new();
    for (name, def) in &doc.fonts {
        if let Ok(bytes) = BASE64.decode(&def.data) {
            if let Some(font) = Font::new(Arc::new(bytes).into(), 0) {
                fonts.insert(name.clone(), font);
            }
        }
    }
    let fallback = fonts.values().next().cloned();

    for page in &doc.pages {
        let settings = page_settings_or_default(page.width, page.height);
        let mut pg = document.start_page_with(settings);
        let mut surface = pg.surface();

        for element in &page.elements {
            match element {
                PdfElement::Text {
                    content,
                    x,
                    y,
                    font_size,
                    font,
                    color,
                } => {
                    let resolved = fonts.get(font).or(fallback.as_ref()).cloned();
                    if let Some(f) = resolved {
                        let ky = page.height - y;
                        surface.set_fill(Some(fill_from(color)));
                        surface.set_stroke(None);
                        surface.draw_text(
                            Point::from_xy(*x, ky),
                            f,
                            *font_size,
                            content,
                            false,
                            TextDirection::Auto,
                        );
                    }
                }

                PdfElement::Rect {
                    x,
                    y,
                    width,
                    height,
                    fill_color,
                    stroke_color,
                    stroke_width,
                    corner_radius,
                } => {
                    // Model: (x,y) = bottom-left in PDF coords; krilla: top-left origin
                    let ky = page.height - y - height;
                    let mut pb = PathBuilder::new();
                    if *corner_radius > 0.0 {
                        push_rounded_rect(&mut pb, *x, ky, *width, *height, *corner_radius);
                    } else if let Some(rect) = Rect::from_xywh(*x, ky, *width, *height) {
                        pb.push_rect(rect);
                    }
                    if let Some(path) = pb.finish() {
                        surface.set_fill(fill_color.as_ref().map(fill_from));
                        surface.set_stroke(stroke_color.as_ref().map(|c| Stroke {
                            paint: paint_from(c),
                            width: *stroke_width,
                            ..Default::default()
                        }));
                        if fill_color.is_none() && stroke_color.is_none() {
                            surface.set_fill(Some(fill_from(&PdfColor::default())));
                        }
                        surface.draw_path(&path);
                    }
                }

                PdfElement::Line {
                    x1,
                    y1,
                    x2,
                    y2,
                    color,
                    stroke_width,
                    ripple,
                    thickness_ripple,
                } => {
                    let ky1 = page.height - y1;
                    let ky2 = page.height - y2;
                    if *thickness_ripple > 0.0 {
                        // Variable-width brush stroke: filled polygon, straight path
                        let mut pb = PathBuilder::new();
                        push_brush_stroke(&mut pb, *x1, ky1, *x2, ky2, *stroke_width, *thickness_ripple);
                        if let Some(path) = pb.finish() {
                            surface.set_fill(Some(fill_from(color)));
                            surface.set_stroke(None);
                            surface.draw_path(&path);
                        }
                    } else {
                        let mut pb = PathBuilder::new();
                        if *ripple > 0.0 {
                            push_rippled_line(&mut pb, *x1, ky1, *x2, ky2, *ripple);
                        } else {
                            pb.move_to(*x1, ky1);
                            pb.line_to(*x2, ky2);
                        }
                        if let Some(path) = pb.finish() {
                            surface.set_fill(None);
                            surface.set_stroke(Some(Stroke {
                                paint: paint_from(color),
                                width: *stroke_width,
                                ..Default::default()
                            }));
                            surface.draw_path(&path);
                        }
                    }
                }

                PdfElement::Image {
                    x,
                    y,
                    width,
                    height,
                    data,
                    format,
                } => {
                    if let Ok(bytes) = BASE64.decode(data) {
                        let data: krilla::Data = Arc::new(bytes).into();
                        let image = match format.as_str() {
                            "jpeg" | "jpg" => krilla::image::Image::from_jpeg(data, true),
                            _ => krilla::image::Image::from_png(data, true),
                        };
                        if let Ok(img) = image {
                            let ky = page.height - y - height;
                            if let Some(size) = Size::from_wh(*width, *height) {
                                surface.push_transform(&Transform::from_translate(*x, ky));
                                surface.draw_image(img, size);
                                surface.pop();
                            }
                        }
                    }
                }

                PdfElement::Sector {
                    cx,
                    cy,
                    radius,
                    start_angle,
                    sweep_angle,
                    fill_color,
                    ripple,
                    seed,
                    mirror,
                } => {
                    let kcx = *cx;
                    let kcy = page.height - cy;
                    let mut pb = PathBuilder::new();
                    if *ripple > 0.0 {
                        push_rippled_sector(&mut pb, kcx, kcy, *radius, *start_angle, *sweep_angle, *ripple, *seed, *mirror);
                    } else {
                        pb.move_to(kcx, kcy);
                        push_arc(&mut pb, kcx, kcy, *radius, *start_angle, *sweep_angle);
                    }
                    pb.close();
                    if let Some(path) = pb.finish() {
                        surface.set_fill(fill_color.as_ref().map(fill_from));
                        surface.set_stroke(None);
                        surface.draw_path(&path);
                    }
                }

                PdfElement::Polygon {
                    points,
                    fill_color,
                } => {
                    if points.len() >= 3 {
                        let mut pb = PathBuilder::new();
                        pb.move_to(points[0].x, page.height - points[0].y);
                        for p in &points[1..] {
                            pb.line_to(p.x, page.height - p.y);
                        }
                        pb.close();
                        if let Some(path) = pb.finish() {
                            surface.set_fill(fill_color.as_ref().map(fill_from));
                            surface.set_stroke(None);
                            surface.draw_path(&path);
                        }
                    }
                }

                PdfElement::Polyline {
                    points,
                    color,
                    stroke_width,
                    thickness_ripple,
                } => {
                    if points.len() >= 2 {
                        let kpts: Vec<(f32, f32)> = points
                            .iter()
                            .map(|p| (p.x, page.height - p.y))
                            .collect();
                        if *thickness_ripple > 0.0 {
                            let mut pb = PathBuilder::new();
                            push_brush_polyline(&mut pb, &kpts, *stroke_width, *thickness_ripple);
                            if let Some(path) = pb.finish() {
                                surface.set_fill(Some(fill_from(color)));
                                surface.set_stroke(None);
                                surface.draw_path(&path);
                            }
                        } else {
                            let mut pb = PathBuilder::new();
                            pb.move_to(kpts[0].0, kpts[0].1);
                            for &(x, y) in &kpts[1..] {
                                pb.line_to(x, y);
                            }
                            if let Some(path) = pb.finish() {
                                surface.set_fill(None);
                                surface.set_stroke(Some(Stroke {
                                    paint: paint_from(color),
                                    width: *stroke_width,
                                    line_cap: LineCap::Round,
                                    line_join: LineJoin::Round,
                                    ..Default::default()
                                }));
                                surface.draw_path(&path);
                            }
                        }
                    }
                }

                PdfElement::Svg {
                    content,
                    x,
                    y,
                    width,
                    height,
                } => {
                    let opts = usvg::Options::default();
                    if let Ok(tree) = usvg::Tree::from_str(content, &opts) {
                        let ky = page.height - y - height;
                        if let Some(size) = Size::from_wh(*width, *height) {
                            surface.push_transform(&Transform::from_translate(*x, ky));
                            surface.draw_svg(&tree, size, SvgSettings::default());
                            surface.pop();
                        }
                    }
                }

                PdfElement::ClipStart {
                    x,
                    y,
                    width,
                    height,
                    corner_radius,
                } => {
                    let ky = page.height - y - height;
                    let mut pb = PathBuilder::new();
                    if *corner_radius > 0.0 {
                        push_rounded_rect(&mut pb, *x, ky, *width, *height, *corner_radius);
                    } else if let Some(rect) = Rect::from_xywh(*x, ky, *width, *height) {
                        pb.push_rect(rect);
                    }
                    if let Some(path) = pb.finish() {
                        surface.push_clip_path(&path, &FillRule::NonZero);
                    }
                }

                PdfElement::ClipEnd {} => {
                    surface.pop();
                }
            }
        }

        surface.finish();
        pg.finish();
    }

    document.finish().unwrap_or_default()
}

/// Create page settings, falling back to A4 if the dimensions are invalid (zero, negative, NaN).
fn page_settings_or_default(width: f32, height: f32) -> PageSettings {
    PageSettings::from_wh(width, height)
        .unwrap_or_else(|| PageSettings::from_wh(595.0, 842.0).unwrap())
}

fn paint_from(color: &PdfColor) -> krilla::paint::Paint {
    rgb::Color::new(
        (color.r * 255.0) as u8,
        (color.g * 255.0) as u8,
        (color.b * 255.0) as u8,
    )
    .into()
}

fn fill_from(color: &PdfColor) -> Fill {
    Fill {
        paint: paint_from(color),
        opacity: NormalizedF32::ONE,
        rule: Default::default(),
    }
}

/// Append a circular arc to the path.
/// `cx, cy` = center (in krilla coords, y-down).
/// `start_angle` and `sweep_angle` are in degrees.
/// Angles use standard math convention (0° = right, CCW positive)
/// but y is flipped for krilla (y-down), so visually CW.
fn push_arc(pb: &mut PathBuilder, cx: f32, cy: f32, r: f32, start_deg: f32, sweep_deg: f32) {
    let n = ((sweep_deg.abs() / 90.0).ceil() as usize).max(1);
    let step = sweep_deg / n as f32;
    let mut angle = start_deg;
    // First point on arc
    let sa = angle.to_radians();
    pb.line_to(cx + r * sa.cos(), cy - r * sa.sin());
    for _ in 0..n {
        let a1 = angle.to_radians();
        let a2 = (angle + step).to_radians();
        let half = ((a2 - a1) / 2.0).tan();
        let alpha = (a2 - a1).sin() * ((4.0 + 3.0 * half * half).sqrt() - 1.0) / 3.0;
        let x1 = cx + r * a1.cos();
        let y1 = cy - r * a1.sin();
        let x2 = cx + r * a2.cos();
        let y2 = cy - r * a2.sin();
        let cp1x = x1 - alpha * r * a1.sin();
        let cp1y = y1 - alpha * r * a1.cos();
        let cp2x = x2 + alpha * r * a2.sin();
        let cp2y = y2 + alpha * r * a2.cos();
        pb.cubic_to(cp1x, cp1y, cp2x, cp2y, x2, y2);
        angle += step;
    }
}

/// Append a rippled radial edge (center ↔ arc endpoint) to the path.
/// Perpendicular sinusoidal wiggle tapered from 0 at center to full at the outer end.
/// Trace the full sector outline as one continuous shape whose wave flows smoothly
/// around corners.
///
/// The base path has geometric fillets at the two outer corners (where radial edges
/// meet the arc).  Each fillet is a true circular arc tangent to both the radial line
/// and the main arc, so the path has continuous normals everywhere.  A single
/// sinusoidal wave displaced along the outward normal flows naturally around the
/// entire perimeter with no kinks.
fn push_rippled_sector(
    pb: &mut PathBuilder,
    cx: f32,
    cy: f32,
    r: f32,
    start_deg: f32,
    sweep_deg: f32,
    amplitude: f32,
    seed: i32,
    mirror: bool,
) {
    let start_rad = start_deg.to_radians();
    let end_deg = start_deg + sweep_deg;
    let end_rad = end_deg.to_radians();
    let sigma: f32 = if sweep_deg >= 0.0 { 1.0 } else { -1.0 };

    // Fillet radius — scaled by corner internal angle, clamped to avoid overlap.
    // For a sector, the internal angle at each outer corner is 90° (radial ⊥ arc tangent).
    // tan(θ/2) scales the fillet: larger angles → larger fillet → smoother transition.
    // 90° → tan(45°) = 1.0, 120° → 1.73, 60° → 0.58
    let corner_angle = std::f32::consts::FRAC_PI_2; // 90° for sector outer corners
    let angle_scale = (corner_angle / 2.0).tan();
    let sweep_rad = sweep_deg.to_radians().abs();
    let max_f = (r * 0.5).min(r * (sweep_rad / 4.0).tan()); // half-sweep tangent limit
    let f = (2.5 * amplitude * angle_scale).min(max_f).max(0.0);

    if f < 0.01 || sweep_rad < 0.01 {
        // Degenerate: fall back to sharp sector
        pb.move_to(cx, cy);
        push_arc(pb, cx, cy, r, start_deg, sweep_deg);
        return;
    }

    let cos_s = start_rad.cos();
    let sin_s = start_rad.sin();
    let cos_e = end_rad.cos();
    let sin_e = end_rad.sin();

    // Radial unit vectors (center → outer edge) in krilla coords (y-down).
    let u_s = (cos_s, -sin_s);
    let u_e = (cos_e, -sin_e);

    // Inward perpendicular (toward sector interior) for each radial.
    let v_s = (-sigma * sin_s, -sigma * cos_s);
    let v_e = (sigma * sin_e, sigma * cos_e);

    // Fillet geometry: fillet center lies at distance rho = r-f from sector center,
    // along the bisector between the radial and the arc tangent at the corner.
    let rho = r - f;
    let x0 = ((rho * rho - f * f).max(0.0)).sqrt();

    // Fillet centers
    let c1 = (cx + x0 * u_s.0 + f * v_s.0, cy + x0 * u_s.1 + f * v_s.1);
    let c2 = (cx + x0 * u_e.0 + f * v_e.0, cy + x0 * u_e.1 + f * v_e.1);

    // Tangent points on radial lines (where radials end and fillets begin)
    let r1 = (cx + x0 * u_s.0, cy + x0 * u_s.1);
    let r2 = (cx + x0 * u_e.0, cy + x0 * u_e.1);

    // Tangent points on main arc (where fillets end and arc begins)
    let a1 = (cx + (r / rho) * (c1.0 - cx), cy + (r / rho) * (c1.1 - cy));
    let a2 = (cx + (r / rho) * (c2.0 - cx), cy + (r / rho) * (c2.1 - cy));

    // Angular trim on the main arc
    let delta = f.atan2(x0); // radians consumed by each fillet on the arc

    // Fillet arc angles (local to each fillet center)
    fn wrap_pi(mut a: f32) -> f32 {
        let pi = std::f32::consts::PI;
        while a <= -pi { a += 2.0 * pi; }
        while a > pi { a -= 2.0 * pi; }
        a
    }
    // Fillet 1: from radial tangent point to arc tangent point
    // Force sweep direction from sector winding to avoid atan2 branch ambiguity
    let f1_phi0 = (r1.1 - c1.1).atan2(r1.0 - c1.0);
    let f1_phi1 = (a1.1 - c1.1).atan2(a1.0 - c1.0);
    let f1_dphi_raw = wrap_pi(f1_phi1 - f1_phi0);
    let f1_dphi = -sigma * f1_dphi_raw.abs(); // follow sector winding

    // Fillet 2: from arc tangent point to radial tangent point
    let f2_phi0 = (a2.1 - c2.1).atan2(a2.0 - c2.0);
    let f2_phi1 = (r2.1 - c2.1).atan2(r2.0 - c2.0);
    let f2_dphi_raw = wrap_pi(f2_phi1 - f2_phi0);
    let f2_dphi = -sigma * f2_dphi_raw.abs(); // follow sector winding

    // Main arc: force correct direction from sweep sign, don't rely on wrap_pi
    let main_start_rad = (a1.1 - cy).atan2(a1.0 - cx);
    let trimmed_sweep = (sweep_rad - 2.0 * delta).max(0.0);
    let main_dphi = -sigma * trimmed_sweep; // positive sweep → CCW in math → CW in krilla (negative atan2 direction)

    // Segment lengths for cumulative distance (outer fillets + arc)
    let len_rad = ((r1.0 - cx) * (r1.0 - cx) + (r1.1 - cy) * (r1.1 - cy)).sqrt();
    let len_f1 = f * f1_dphi.abs();
    let len_f2 = f * f2_dphi.abs();
    let len_main_arc = r * trimmed_sweep;
    let len_rad2 = ((r2.0 - cx) * (r2.0 - cx) + (r2.1 - cy) * (r2.1 - cy)).sqrt();

    // --- Center local circular arc geometry ---
    // Snapshot + local corner replacement: compute everything from local vectors,
    // no global angle winding or wrap_pi. Guaranteed tangent continuity.
    let max_t_center = (x0 - 0.001_f32).max(0.0);
    let t_center = (3.0 * amplitude).min(r * 0.15).min(max_t_center);

    // Tangent points on radials (inner end, distance t_center from center)
    let ts = (cx + t_center * u_s.0, cy + t_center * u_s.1);
    let te = (cx + t_center * u_e.0, cy + t_center * u_e.1);

    // Local edge directions from center vertex V=(cx,cy)
    let e1 = (u_e.0, u_e.1); // toward te (end radial)
    let e2 = (u_s.0, u_s.1); // toward ts (start radial)

    // Local fillet geometry
    // Use analytical sweep angle instead of dot-product acos to ensure identical
    // geometry for sectors with the same radius/sweep regardless of position/rotation.
    let alpha = sweep_rad;
    let bisect_raw = (e1.0 + e2.0, e1.1 + e2.1);
    let bisect_len = (bisect_raw.0 * bisect_raw.0 + bisect_raw.1 * bisect_raw.1).sqrt().max(1e-6);
    let b = (bisect_raw.0 / bisect_len, bisect_raw.1 / bisect_len); // interior bisector
    let d_center = t_center / (alpha * 0.5).cos();
    let fc_r = t_center * (alpha * 0.5).tan(); // fillet radius
    let fc_center = (cx + d_center * b.0, cy + d_center * b.1);

    // Local arc parameterization: from te to ts around fc_center
    let n1_local = ((te.0 - fc_center.0) / fc_r, (te.1 - fc_center.1) / fc_r);
    let n2_local = ((ts.0 - fc_center.0) / fc_r, (ts.1 - fc_center.1) / fc_r);
    // Two possible arcs — choose the one whose midpoint is CLOSER to center vertex V=(cx,cy)
    // This ensures we get the concave (inward) arc, not the convex (outward) one.
    let th1 = (n1_local.0 * n2_local.1 - n1_local.1 * n2_local.0)
        .atan2(n1_local.0 * n2_local.0 + n1_local.1 * n2_local.1);
    let th2 = if th1 >= 0.0 { th1 - std::f32::consts::TAU } else { th1 + std::f32::consts::TAU };

    let mid_dist = |th: f32| -> f32 {
        let a = 0.5 * th;
        let ca = a.cos();
        let sa = a.sin();
        let nr = (n1_local.0 * ca - n1_local.1 * sa, n1_local.0 * sa + n1_local.1 * ca);
        let pm = (fc_center.0 + fc_r * nr.0, fc_center.1 + fc_r * nr.1);
        let dx = pm.0 - cx;
        let dy = pm.1 - cy;
        dx * dx + dy * dy
    };
    let theta = if mid_dist(th1) < mid_dist(th2) { th1 } else { th2 };

    let len_fc = fc_r * theta.abs();

    // Recompute radial effective lengths (trimmed at both ends by outer + center fillets)
    let len_rad1_eff = (len_rad - t_center).max(0.0);
    let len_rad2_eff = (len_rad2 - t_center).max(0.0);

    // Analytical total perimeter length — computed purely from radius, sweep, and amplitude
    // so that identical sectors produce identical wave patterns regardless of position/rotation.
    let analytical_len_fc = fc_r * (std::f32::consts::PI - sweep_rad).abs();
    let analytical_len_rad = (x0 - t_center).max(0.0);
    let analytical_len_fillet = f * std::f32::consts::FRAC_PI_2; // 90° outer corners
    let total_len = analytical_len_fc
        + 2.0 * analytical_len_rad
        + 2.0 * analytical_len_fillet
        + r * trimmed_sweep;

    // Wave function — continuous over the full perimeter, ~3 peaks.
    // The seed offsets the phase so all sectors sharing a seed produce identical organic shapes.
    // When mirror=true, the cumulative distance runs backward so the wave pattern is reversed,
    // creating a mirror-reflected organic shape (used for adjacent quadrants).
    let s = seed as f64;
    let p = total_len as f64;
    let wave_at = |d: f64| -> f32 {
        let d = if mirror { p - d } else { d };
        let tau = std::f64::consts::TAU;
        let w = 0.50 * (3.0 * tau * d / p + 0.5 + s).sin()
              + 0.30 * (5.0 * tau * d / p + 2.3 + s * 1.618).sin()
              + 0.20 * (7.0 * tau * d / p + 4.1 + s * 2.236).sin();
        amplitude * w as f32
    };

    // Point collection: (x, y, cumulative_d, normal_x, normal_y, taper)
    // taper=1.0 for full wave, 0.0 at center patch endpoints for smooth joins
    let mut pts: Vec<(f32, f32, f32, f32, f32, f32)> = Vec::new();

    let radial_steps = 16_usize;
    let fillet_steps = 8_usize;
    let main_arc_steps = (main_dphi.abs().to_degrees() / 1.5).ceil().max(1.0) as usize;

    // Radial outward normals (perpendicular, away from sector interior)
    let n1x = -v_s.0;
    let n1y = -v_s.1;
    let n2x = -v_e.0;
    let n2y = -v_e.1;

    // --- Segment 0: Center local arc (te → ts, around fc_center) ---
    let center_steps = 10_usize;
    for i in 0..=center_steps {
        let u = i as f32 / center_steps as f32;
        let angle = u * theta;
        let cos_a = angle.cos();
        let sin_a = angle.sin();
        let nr = (n1_local.0 * cos_a - n1_local.1 * sin_a,
                  n1_local.0 * sin_a + n1_local.1 * cos_a);
        let px = fc_center.0 + fc_r * nr.0;
        let py = fc_center.1 + fc_r * nr.1;
        // taper=0: no ripple on center patch — keeps it a clean arc
        pts.push((px, py, u * len_fc, nr.0, nr.1, 0.0));
    }
    let mut cum_d: f32 = len_fc;

    // --- Segment 1: Radial 1 (ts → r1) ---
    // ts is at t_center along start radial, r1 is at x0 along start radial
    let join_frac = 0.18_f32;
    for i in 1..=radial_steps {
        let t = i as f32 / radial_steps as f32;
        let px = ts.0 + t * (r1.0 - ts.0);
        let py = ts.1 + t * (r1.1 - ts.1);
        let d = t * len_rad1_eff;
        // Taper: 0 at ts (t=0), ramp up
        let e = (t / join_frac).clamp(0.0, 1.0);
        let taper = e * e * (3.0 - 2.0 * e);
        pts.push((px, py, cum_d + d, n1x, n1y, taper));
    }
    cum_d += len_rad1_eff;

    // --- Segment 2: Outer fillet 1 (r1 → a1) ---
    for i in 1..=fillet_steps {
        let t = i as f32 / fillet_steps as f32;
        let phi = f1_phi0 + t * f1_dphi;
        let px = c1.0 + f * phi.cos();
        let py = c1.1 + f * phi.sin();
        let nx = phi.cos();
        let ny = phi.sin();
        pts.push((px, py, cum_d + t * len_f1, nx, ny, 1.0));
    }
    cum_d += len_f1;

    // --- Segment 3: Main arc (a1 → a2) ---
    for i in 1..=main_arc_steps {
        let t = i as f32 / main_arc_steps as f32;
        let phi = main_start_rad + t * main_dphi;
        let px = cx + r * phi.cos();
        let py = cy + r * phi.sin();
        let nx = phi.cos();
        let ny = phi.sin();
        pts.push((px, py, cum_d + t * len_main_arc, nx, ny, 1.0));
    }
    cum_d += len_main_arc;

    // --- Segment 4: Outer fillet 2 (a2 → r2) ---
    for i in 1..=fillet_steps {
        let t = i as f32 / fillet_steps as f32;
        let phi = f2_phi0 + t * f2_dphi;
        let px = c2.0 + f * phi.cos();
        let py = c2.1 + f * phi.sin();
        let nx = phi.cos();
        let ny = phi.sin();
        pts.push((px, py, cum_d + t * len_f2, nx, ny, 1.0));
    }
    cum_d += len_f2;

    // --- Segment 5: Radial 2 (r2 → te) ---
    // r2 is at x0 along end radial, te is at t_center along end radial
    for i in 1..radial_steps {
        let t = i as f32 / radial_steps as f32;
        let px = r2.0 + t * (te.0 - r2.0);
        let py = r2.1 + t * (te.1 - r2.1);
        // Taper: 0 at te (t=1), ramp down
        let e = ((1.0 - t) / join_frac).clamp(0.0, 1.0);
        let taper = e * e * (3.0 - 2.0 * e);
        pts.push((px, py, cum_d + t * len_rad2_eff, n2x, n2y, taper));
    }

    // --- Build the displaced path ---
    // Start at the first point (te, start of center Bézier)
    let (sx, sy, _, _, _, _) = pts[0];
    pb.move_to(sx, sy);
    for i in 1..pts.len() {
        let (bx, by, d, nx, ny, taper) = pts[i];
        let w = wave_at(d as f64) * taper;
        pb.line_to(bx + w * nx, by + w * ny);
    }
    // Close back to start point (continuous closed path, no center vertex).
}

/// Render a straight line as a filled polygon with variable thickness (brush stroke).
/// The line stays straight but its width oscillates using triple-harmonic modulation.
fn push_brush_stroke(
    pb: &mut PathBuilder,
    x1: f32,
    y1: f32,
    x2: f32,
    y2: f32,
    base_width: f32,
    amplitude: f32,
) {
    let dx = x2 - x1;
    let dy = y2 - y1;
    let len = (dx * dx + dy * dy).sqrt();
    if len < 0.001 {
        return;
    }
    // Perpendicular unit vector
    let px = -dy / len;
    let py = dx / len;

    let steps = (len / 2.0).ceil() as usize;
    let half_base = base_width / 2.0;

    // Collect points along both edges
    let mut top: Vec<(f32, f32)> = Vec::with_capacity(steps + 1);
    let mut bot: Vec<(f32, f32)> = Vec::with_capacity(steps + 1);

    for i in 0..=steps {
        let t = i as f64 / steps as f64;
        let bx = x1 + t as f32 * dx;
        let by = y1 + t as f32 * dy;
        // Independent waves per side — distance-based for consistent scale
        let d = t * len as f64;
        let wave_top = 0.50 * (0.12 * d + 0.7).sin()
                     + 0.30 * (0.27 * d + 1.4).sin()
                     + 0.20 * (0.45 * d + 4.8).sin();
        let wave_bot = 0.50 * (0.14 * d + 3.2).sin()
                     + 0.30 * (0.29 * d + 0.6).sin()
                     + 0.20 * (0.48 * d + 5.5).sin();
        let half_top = half_base + amplitude * wave_top as f32;
        let half_bot = half_base + amplitude * wave_bot as f32;
        top.push((bx + half_top * px, by + half_top * py));
        bot.push((bx - half_bot * px, by - half_bot * py));
    }

    // Trace: top edge forward, bottom edge backward → closed polygon
    pb.move_to(top[0].0, top[0].1);
    for &(x, y) in &top[1..] {
        pb.line_to(x, y);
    }
    for &(x, y) in bot.iter().rev() {
        pb.line_to(x, y);
    }
    pb.close();
}

/// Variable-width brush stroke along a multi-segment polyline.
/// Tangent is cosine-blended at interior vertices for smooth rounded joins.
/// Semicircular caps at both endpoints.
fn push_brush_polyline(
    pb: &mut PathBuilder,
    points: &[(f32, f32)],
    base_width: f32,
    amplitude: f32,
) {
    if points.len() < 2 {
        return;
    }
    let n = points.len();
    let half_base = base_width / 2.0;

    // Per-segment direction and cumulative distance at each vertex
    let mut stx: Vec<f32> = Vec::with_capacity(n - 1);
    let mut sty: Vec<f32> = Vec::with_capacity(n - 1);
    let mut slen: Vec<f32> = Vec::with_capacity(n - 1);
    let mut cum_d = vec![0.0f32; n];
    for i in 0..n - 1 {
        let dx = points[i + 1].0 - points[i].0;
        let dy = points[i + 1].1 - points[i].1;
        let l = (dx * dx + dy * dy).sqrt().max(1e-6);
        stx.push(dx / l);
        sty.push(dy / l);
        slen.push(l);
        cum_d[i + 1] = cum_d[i] + l;
    }
    let total_len = cum_d[n - 1];
    if total_len < 0.001 {
        return;
    }

    // Wave functions (same style as push_brush_stroke)
    let wave_top = |d: f64| -> f32 {
        let w = 0.50 * (0.12 * d + 0.7).sin()
            + 0.30 * (0.27 * d + 1.4).sin()
            + 0.20 * (0.45 * d + 4.8).sin();
        half_base + amplitude * w as f32
    };
    let wave_bot = |d: f64| -> f32 {
        let w = 0.50 * (0.14 * d + 3.2).sin()
            + 0.30 * (0.29 * d + 0.6).sin()
            + 0.20 * (0.48 * d + 5.5).sin();
        half_base + amplitude * w as f32
    };

    // Tangent blending radius — capped at the shortest segment length so
    // blending doesn't distort densely-tessellated arcs (e.g. chevron tip fillet).
    let min_seg = slen.iter().cloned().fold(f32::INFINITY, f32::min);
    let blend_r = min_seg.min(half_base * 4.0).min(total_len * 0.2);
    let step = 1.0f32;
    let sample_count = (total_len / step).ceil() as usize;

    let mut tops: Vec<(f32, f32)> = Vec::with_capacity(sample_count + 2);
    let mut bots: Vec<(f32, f32)> = Vec::with_capacity(sample_count + 2);
    let mut first_tx = 0.0f32;
    let mut first_ty = 0.0f32;
    let mut last_tx = 0.0f32;
    let mut last_ty = 0.0f32;

    for i in 0..=sample_count {
        let d = (i as f32 * step).min(total_len);

        // Find segment
        let mut si = 0;
        while si < slen.len() - 1 && cum_d[si + 1] < d - 1e-4 {
            si += 1;
        }

        let local_d = d - cum_d[si];
        let t = (local_d / slen[si]).clamp(0.0, 1.0);
        let px = points[si].0 + t * (points[si + 1].0 - points[si].0);
        let py = points[si].1 + t * (points[si + 1].1 - points[si].1);

        // Smooth tangent via cosine blend near interior vertices
        let mut tx = stx[si];
        let mut ty = sty[si];

        if si > 0 && local_d < blend_r {
            let u = 0.5 * (1.0 + (std::f32::consts::PI * local_d / blend_r).cos());
            tx += u * (stx[si - 1] - tx) * 0.5;
            ty += u * (sty[si - 1] - ty) * 0.5;
        }
        let dist_to_end = slen[si] - local_d;
        if si < slen.len() - 1 && dist_to_end < blend_r {
            let u = 0.5 * (1.0 + (std::f32::consts::PI * dist_to_end / blend_r).cos());
            tx += u * (stx[si + 1] - tx) * 0.5;
            ty += u * (sty[si + 1] - ty) * 0.5;
        }

        let tl = (tx * tx + ty * ty).sqrt().max(1e-6);
        tx /= tl;
        ty /= tl;

        if i == 0 {
            first_tx = tx;
            first_ty = ty;
        }
        if i == sample_count {
            last_tx = tx;
            last_ty = ty;
        }

        let nx = -ty;
        let ny = tx;
        let ht = wave_top(d as f64);
        let hb = wave_bot(d as f64);
        tops.push((px + ht * nx, py + ht * ny));
        bots.push((px - hb * nx, py - hb * ny));
    }

    // Build polygon: start cap → top edge → end cap → bot edge reversed
    let cap_steps = 8;

    // Start cap: semicircle from bot[0] → top[0] going backward (-tangent)
    pb.move_to(bots[0].0, bots[0].1);
    {
        let (cx, cy) = points[0];
        let nx = -first_ty;
        let ny = first_tx;
        let r = (wave_top(0.0) + wave_bot(0.0)) / 2.0;
        for i in 1..=cap_steps {
            let theta = std::f32::consts::PI * i as f32 / cap_steps as f32;
            // Rotate from -normal through -tangent to +normal
            let px = cx - r * (nx * theta.cos() + first_tx * theta.sin());
            let py = cy - r * (ny * theta.cos() + first_ty * theta.sin());
            pb.line_to(px, py);
        }
    }

    // Top edge forward
    for &(x, y) in &tops {
        pb.line_to(x, y);
    }

    // End cap: semicircle from top[last] → bot[last] going forward (+tangent)
    {
        let (cx, cy) = points[n - 1];
        let nx = -last_ty;
        let ny = last_tx;
        let r = (wave_top(total_len as f64) + wave_bot(total_len as f64)) / 2.0;
        for i in 1..=cap_steps {
            let theta = std::f32::consts::PI * i as f32 / cap_steps as f32;
            // Rotate from +normal through +tangent to -normal
            let px = cx + r * (nx * theta.cos() + last_tx * theta.sin());
            let py = cy + r * (ny * theta.cos() + last_ty * theta.sin());
            pb.line_to(px, py);
        }
    }

    // Bot edge backward
    for &(x, y) in bots.iter().rev() {
        pb.line_to(x, y);
    }
    pb.close();
}

/// Append a rippled (hand-drawn) straight line to the path.
/// Triple-harmonic sinusoidal perturbation perpendicular to the line direction.
fn push_rippled_line(
    pb: &mut PathBuilder,
    x1: f32,
    y1: f32,
    x2: f32,
    y2: f32,
    amplitude: f32,
) {
    let dx = x2 - x1;
    let dy = y2 - y1;
    let len = (dx * dx + dy * dy).sqrt();
    if len < 0.001 {
        pb.move_to(x1, y1);
        return;
    }
    // Unit direction and perpendicular
    let ux = dx / len;
    let uy = dy / len;
    let px = -uy;
    let py = ux;

    let steps = (len / 2.0).ceil() as usize;
    pb.move_to(x1, y1);
    for i in 1..=steps {
        let t = i as f64 / steps as f64;
        let bx = x1 + t as f32 * dx;
        let by = y1 + t as f32 * dy;
        let d = t * len as f64;
        let wave = 0.50 * (0.12 * d + 0.7).sin()
                 + 0.30 * (0.27 * d + 2.1).sin()
                 + 0.20 * (0.45 * d + 3.9).sin();
        let offset = amplitude * wave as f32;
        pb.line_to(bx + offset * px, by + offset * py);
    }
}

fn push_rounded_rect(pb: &mut PathBuilder, x: f32, y: f32, w: f32, h: f32, r: f32) {
    let r = r.min(w / 2.0).min(h / 2.0);
    pb.move_to(x + r, y);
    pb.line_to(x + w - r, y);
    pb.quad_to(x + w, y, x + w, y + r);
    pb.line_to(x + w, y + h - r);
    pb.quad_to(x + w, y + h, x + w - r, y + h);
    pb.line_to(x + r, y + h);
    pb.quad_to(x, y + h, x, y + h - r);
    pb.line_to(x, y + r);
    pb.quad_to(x, y, x + r, y);
    pb.close();
}

// ── Tree-based rendering (parley) ───────────────────────────────────────────

pub fn render_tree(doc: &TreeDocument) -> Vec<u8> {
    let mut krilla_doc = Document::new();

    // Set up parley font context and register all document fonts
    let mut font_cx = FontContext::default();
    let mut layout_cx: LayoutContext<PdfColor> = LayoutContext::new();
    let (_font_blobs, font_name_map) = register_fonts(&mut font_cx, &doc.fonts);

    // Also create krilla fonts for non-text element rendering (existing draw_text path)
    let mut krilla_fonts: HashMap<String, Font> = HashMap::new();
    for (name, def) in &doc.fonts {
        if let Ok(bytes) = BASE64.decode(&def.data) {
            if let Some(font) = Font::new(Arc::new(bytes).into(), 0) {
                krilla_fonts.insert(name.clone(), font);
            }
        }
    }
    let fallback_font = krilla_fonts.values().next().cloned();

    for tree_page in &doc.pages {
        let margin = &tree_page.margin;
        let content_x = margin.left;
        let content_y = margin.top;
        let content_width = (tree_page.width - margin.left - margin.right).max(0.0);
        let content_height = (tree_page.height - margin.top - margin.bottom).max(0.0);

        // Determine the default font name (first registered font)
        let default_font = doc.fonts.keys().next()
            .cloned()
            .unwrap_or_default();

        let mut engine = LayoutEngine::new(&mut font_cx, &mut layout_cx, default_font, font_name_map.clone());

        // Layout + paginate
        let output_pages = paginate(
            &mut engine,
            &tree_page.content,
            content_x,
            content_y,
            content_width,
            0.0, // gap between top-level children (handled by Column/Spacer nodes)
            content_height,
            &tree_page.split_strategy,
        );

        // Render each output page
        for page_result in &output_pages {
            let settings = page_settings_or_default(tree_page.width, tree_page.height);
            let mut pg = krilla_doc.start_page_with(settings);
            let mut surface = pg.surface();

            // Page background
            if let Some(bg) = &tree_page.background {
                let mut pb = PathBuilder::new();
                if let Some(rect) = Rect::from_xywh(0.0, 0.0, tree_page.width, tree_page.height) {
                    pb.push_rect(rect);
                }
                if let Some(path) = pb.finish() {
                    surface.set_fill(Some(fill_from(bg)));
                    surface.set_stroke(None);
                    surface.draw_path(&path);
                }
            }

            // Render each positioned item
            for item in &page_result.items {
                match item {
                    PositionedItem::RichText { text, layout, x, y } => {
                        render_parley_layout(
                            &mut surface,
                            text,
                            layout,
                            *x,
                            *y,
                        );
                    }
                    PositionedItem::Element(element) => {
                        render_element_ydown(
                            &mut surface,
                            element,
                            &krilla_fonts,
                            fallback_font.as_ref(),
                        );
                    }
                }
            }

            surface.finish();
            pg.finish();
        }
    }

    krilla_doc.finish().unwrap_or_default()
}

/// Render a parley Layout to a krilla surface using draw_glyphs().
/// Coordinates are in y-down (krilla-native) space — no conversion needed.
/// Convert parley's normalized variation coordinates (i16 F2Dot14) back to
/// user-space values (e.g. wght=700) that krilla needs for Font::new_variable.
fn denormalize_coords(data: &[u8], index: u32, norm: &[i16]) -> Vec<(Tag, f32)> {
    let Ok(font_ref) = skrifa::FontRef::from_index(data, index) else {
        return vec![];
    };
    let axes: Vec<_> = font_ref.axes().iter().collect();
    norm.iter().enumerate().filter_map(|(i, &nc)| {
        let axis = axes.get(i)?;
        let f = nc as f32 / 16384.0; // F2Dot14 → float
        let val = if f < 0.0 {
            axis.default_value() + f * (axis.default_value() - axis.min_value())
        } else {
            axis.default_value() + f * (axis.max_value() - axis.default_value())
        };
        Some((Tag::new(&axis.tag().to_be_bytes()), val))
    }).collect()
}

fn render_parley_layout(
    surface: &mut krilla::surface::Surface,
    text: &str,
    layout: &parley::Layout<PdfColor>,
    origin_x: f32,
    origin_y: f32,
) {
    // Cache key: (blob_id, normalized_coords) — different variation instances
    // of the same variable font get separate krilla Font objects.
    let mut font_cache: HashMap<(u64, Vec<i16>), Option<Font>> = HashMap::new();

    for line in layout.lines() {
        let baseline = line.metrics().baseline;
        let ky = origin_y + baseline;
        let mut x = origin_x + line.metrics().offset;

        for run in line.runs() {
            let mut cur_x = x;
            let font_data = run.font().clone();
            let (arc_data, blob_id) = font_data.data.into_raw_parts();
            let norm: Vec<i16> = run.normalized_coords().iter()
                .map(|c| { let bits: [u8; 2] = c.to_be_bytes(); i16::from_be_bytes(bits) })
                .collect();

            let cache_key = (blob_id, norm.clone());
            let krilla_font = font_cache
                .entry(cache_key)
                .or_insert_with(|| {
                    if norm.is_empty() {
                        Font::new(arc_data.clone().into(), font_data.index)
                    } else {
                        let raw: &[u8] = (*arc_data).as_ref();
                        let user_coords = denormalize_coords(raw, font_data.index, &norm);
                        Font::new_variable(arc_data.clone().into(), font_data.index, &user_coords)
                            .or_else(|| Font::new(arc_data.clone().into(), font_data.index))
                    }
                });
            let Some(krilla_font) = krilla_font else { continue };

            let font_size = run.font_size();
            let mut cur_style: Option<u16> = None;
            let mut glyphs = Vec::<KrillaGlyph>::new();

            for cluster in run.visual_clusters() {
                if cluster.is_ligature_continuation() {
                    if let Some(glyph) = glyphs.last_mut() {
                        glyph.text_range.end = cluster.text_range().end;
                    }
                    continue;
                }

                for glyph in cluster.glyphs() {
                    let glyph_style = glyph.style_index;

                    if let Some(style) = cur_style {
                        if style != glyph_style {
                            // Flush glyphs with previous style
                            let brush = &layout.styles()[style as usize].brush;
                            surface.set_fill(Some(fill_from(brush)));
                            surface.set_stroke(None);
                            surface.draw_glyphs(
                                Point::from_xy(cur_x, ky),
                                &glyphs,
                                krilla_font.clone(),
                                text,
                                font_size,
                                false,
                            );
                            glyphs.clear();
                            cur_x = x;
                            cur_style = Some(glyph_style);
                        }
                    } else {
                        cur_style = Some(glyph_style);
                    }

                    glyphs.push(KrillaGlyph::new(
                        GlyphId::new(glyph.id),
                        glyph.advance / font_size,
                        glyph.x / font_size,
                        glyph.y / font_size,
                        0.0,
                        cluster.text_range(),
                        None,
                    ));
                    x += glyph.advance;
                }
            }

            // Flush remaining glyphs
            if !glyphs.is_empty() {
                if let Some(style) = cur_style {
                    let brush = &layout.styles()[style as usize].brush;
                    surface.set_fill(Some(fill_from(brush)));
                    surface.set_stroke(None);
                    surface.draw_glyphs(
                        Point::from_xy(cur_x, ky),
                        &glyphs,
                        krilla_font.clone(),
                        text,
                        font_size,
                        false,
                    );
                }
            }
        }
    }
}

/// Render a single PdfElement whose coordinates are already in y-down (krilla-native) space.
/// Used by the tree rendering path where the layout engine produces y-down coords directly.
fn render_element_ydown(
    surface: &mut krilla::surface::Surface,
    element: &PdfElement,
    fonts: &HashMap<String, Font>,
    fallback: Option<&Font>,
) {
    match element {
        PdfElement::Text { content, x, y, font_size, font, color } => {
            let resolved = fonts.get(font).or(fallback).cloned();
            if let Some(f) = resolved {
                surface.set_fill(Some(fill_from(color)));
                surface.set_stroke(None);
                surface.draw_text(
                    Point::from_xy(*x, *y), f, *font_size, content,
                    false, TextDirection::Auto,
                );
            }
        }
        PdfElement::Rect { x, y, width, height, fill_color, stroke_color, stroke_width, corner_radius } => {
            let mut pb = PathBuilder::new();
            if *corner_radius > 0.0 {
                push_rounded_rect(&mut pb, *x, *y, *width, *height, *corner_radius);
            } else if let Some(rect) = Rect::from_xywh(*x, *y, *width, *height) {
                pb.push_rect(rect);
            }
            if let Some(path) = pb.finish() {
                surface.set_fill(fill_color.as_ref().map(fill_from));
                surface.set_stroke(stroke_color.as_ref().map(|c| Stroke {
                    paint: paint_from(c),
                    width: *stroke_width,
                    ..Default::default()
                }));
                if fill_color.is_none() && stroke_color.is_none() {
                    surface.set_fill(Some(fill_from(&PdfColor::default())));
                }
                surface.draw_path(&path);
            }
        }
        PdfElement::Line { x1, y1, x2, y2, color, stroke_width, ripple, thickness_ripple } => {
            if *thickness_ripple > 0.0 {
                let mut pb = PathBuilder::new();
                push_brush_stroke(&mut pb, *x1, *y1, *x2, *y2, *stroke_width, *thickness_ripple);
                if let Some(path) = pb.finish() {
                    surface.set_fill(Some(fill_from(color)));
                    surface.set_stroke(None);
                    surface.draw_path(&path);
                }
            } else {
                let mut pb = PathBuilder::new();
                if *ripple > 0.0 {
                    push_rippled_line(&mut pb, *x1, *y1, *x2, *y2, *ripple);
                } else {
                    pb.move_to(*x1, *y1);
                    pb.line_to(*x2, *y2);
                }
                if let Some(path) = pb.finish() {
                    surface.set_fill(None);
                    surface.set_stroke(Some(Stroke {
                        paint: paint_from(color),
                        width: *stroke_width,
                        ..Default::default()
                    }));
                    surface.draw_path(&path);
                }
            }
        }
        PdfElement::Image { x, y, width, height, data, format } => {
            if let Ok(bytes) = BASE64.decode(data) {
                let data: krilla::Data = Arc::new(bytes).into();
                let image = match format.as_str() {
                    "jpeg" | "jpg" => krilla::image::Image::from_jpeg(data, true),
                    _ => krilla::image::Image::from_png(data, true),
                };
                if let Ok(img) = image {
                    if let Some(size) = Size::from_wh(*width, *height) {
                        surface.push_transform(&Transform::from_translate(*x, *y));
                        surface.draw_image(img, size);
                        surface.pop();
                    }
                }
            }
        }
        PdfElement::Sector { cx, cy, radius, start_angle, sweep_angle, fill_color, ripple, seed, mirror } => {
            let mut pb = PathBuilder::new();
            if *ripple > 0.0 {
                push_rippled_sector(&mut pb, *cx, *cy, *radius, *start_angle, *sweep_angle, *ripple, *seed, *mirror);
            } else {
                pb.move_to(*cx, *cy);
                push_arc(&mut pb, *cx, *cy, *radius, *start_angle, *sweep_angle);
            }
            pb.close();
            if let Some(path) = pb.finish() {
                surface.set_fill(fill_color.as_ref().map(fill_from));
                surface.set_stroke(None);
                surface.draw_path(&path);
            }
        }
        PdfElement::Polygon { points, fill_color } => {
            if points.len() >= 3 {
                let mut pb = PathBuilder::new();
                pb.move_to(points[0].x, points[0].y);
                for p in &points[1..] {
                    pb.line_to(p.x, p.y);
                }
                pb.close();
                if let Some(path) = pb.finish() {
                    surface.set_fill(fill_color.as_ref().map(fill_from));
                    surface.set_stroke(None);
                    surface.draw_path(&path);
                }
            }
        }
        PdfElement::Polyline { points, color, stroke_width, thickness_ripple } => {
            if points.len() >= 2 {
                if *thickness_ripple > 0.0 {
                    let kpts: Vec<(f32, f32)> = points.iter().map(|p| (p.x, p.y)).collect();
                    let mut pb = PathBuilder::new();
                    push_brush_polyline(&mut pb, &kpts, *stroke_width, *thickness_ripple);
                    if let Some(path) = pb.finish() {
                        surface.set_fill(Some(fill_from(color)));
                        surface.set_stroke(None);
                        surface.draw_path(&path);
                    }
                } else {
                    let mut pb = PathBuilder::new();
                    pb.move_to(points[0].x, points[0].y);
                    for p in &points[1..] {
                        pb.line_to(p.x, p.y);
                    }
                    if let Some(path) = pb.finish() {
                        surface.set_fill(None);
                        surface.set_stroke(Some(Stroke {
                            paint: paint_from(color),
                            width: *stroke_width,
                            line_cap: LineCap::Round,
                            line_join: LineJoin::Round,
                            ..Default::default()
                        }));
                        surface.draw_path(&path);
                    }
                }
            }
        }
        PdfElement::Svg { content, x, y, width, height } => {
            let opts = usvg::Options::default();
            if let Ok(tree) = usvg::Tree::from_str(content, &opts) {
                if let Some(size) = Size::from_wh(*width, *height) {
                    surface.push_transform(&Transform::from_translate(*x, *y));
                    surface.draw_svg(&tree, size, SvgSettings::default());
                    surface.pop();
                }
            }
        }
        PdfElement::ClipStart { x, y, width, height, corner_radius } => {
            let mut pb = PathBuilder::new();
            if *corner_radius > 0.0 {
                push_rounded_rect(&mut pb, *x, *y, *width, *height, *corner_radius);
            } else if let Some(rect) = Rect::from_xywh(*x, *y, *width, *height) {
                pb.push_rect(rect);
            }
            if let Some(path) = pb.finish() {
                surface.push_clip_path(&path, &FillRule::NonZero);
            }
        }
        PdfElement::ClipEnd {} => {
            surface.pop();
        }
    }
}

