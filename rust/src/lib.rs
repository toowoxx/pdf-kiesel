mod layout;
mod model;
mod paginate;
mod render;
mod tree;

// ---- JNI interface (Android) ----

#[cfg(feature = "android")]
mod jni_bridge {
    use jni::objects::{JClass, JString};
    use jni::sys::jbyteArray;
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_de_toowoxx_pdfkiesel_PdfBridge_generatePdfTree(
        mut env: JNIEnv,
        _class: JClass,
        json: JString,
    ) -> jbyteArray {
        match generate_tree_pdf_from_json(&mut env, &json) {
            Ok(raw) => raw,
            Err(msg) => {
                let _ = env.throw_new("java/lang/RuntimeException", msg);
                std::ptr::null_mut()
            }
        }
    }

    fn generate_tree_pdf_from_json(env: &mut JNIEnv, json: &JString) -> Result<jbyteArray, String> {
        let json_str: String = env
            .get_string(json)
            .map_err(|e| format!("Failed to read JSON string: {e}"))?
            .into();

        let doc: crate::tree::TreeDocument = serde_json::from_str(&json_str)
            .map_err(|e| format!("Failed to parse tree document JSON: {e}"))?;

        let bytes = crate::render::render_tree(&doc);

        env.byte_array_from_slice(&bytes)
            .map(|a| a.into_raw())
            .map_err(|e| format!("Failed to create byte array: {e}"))
    }
}

// ---- C FFI interface (iOS) ----

use std::ffi::CStr;
use std::os::raw::c_char;

/// Result of PDF generation, returned to the caller.
/// The caller must free the buffer with `pdfgen_free`.
#[repr(C)]
pub struct PdfGenResult {
    pub data: *mut u8,
    pub len: usize,
    /// Null on success; points to a null-terminated error string on failure.
    /// The caller must free this with `pdfgen_free_error`.
    pub error: *mut c_char,
}

/// Generate a PDF from a tree-based JSON document.
///
/// # Safety
/// `json` must be a valid null-terminated UTF-8 C string.
#[no_mangle]
pub unsafe extern "C" fn pdfgen_generate_tree(json: *const c_char) -> PdfGenResult {
    if json.is_null() {
        return error_result("null JSON pointer");
    }

    let json_str = match CStr::from_ptr(json).to_str() {
        Ok(s) => s,
        Err(e) => return error_result(&format!("Invalid UTF-8: {e}")),
    };

    let doc: tree::TreeDocument = match serde_json::from_str(json_str) {
        Ok(d) => d,
        Err(e) => return error_result(&format!("Failed to parse tree JSON: {e}")),
    };

    let bytes = render::render_tree(&doc);
    let len = bytes.len();
    let boxed = bytes.into_boxed_slice();
    let ptr = Box::into_raw(boxed) as *mut u8;

    PdfGenResult {
        data: ptr,
        len,
        error: std::ptr::null_mut(),
    }
}

/// Free a PDF buffer returned by `pdfgen_generate_tree`.
///
/// # Safety
/// `data` and `len` must be exactly as returned by `pdfgen_generate_tree`.
#[no_mangle]
pub unsafe extern "C" fn pdfgen_free(data: *mut u8, len: usize) {
    if !data.is_null() {
        let _ = Box::from_raw(std::slice::from_raw_parts_mut(data, len));
    }
}

/// Free an error string returned in `PdfGenResult.error`.
///
/// # Safety
/// `error` must be a pointer returned in `PdfGenResult.error`, or null.
#[no_mangle]
pub unsafe extern "C" fn pdfgen_free_error(error: *mut c_char) {
    if !error.is_null() {
        let _ = std::ffi::CString::from_raw(error);
    }
}

fn error_result(msg: &str) -> PdfGenResult {
    let c_msg = std::ffi::CString::new(msg).unwrap_or_default();
    PdfGenResult {
        data: std::ptr::null_mut(),
        len: 0,
        error: c_msg.into_raw(),
    }
}
