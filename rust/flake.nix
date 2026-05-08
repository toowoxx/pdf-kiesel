{
  description = "pdfgen – Rust PDF renderer for iOS and Android";

  # rust-overlay (oxalica) gives us a Rust toolchain with iOS + Android cross
  # targets bundled. Replaces the earlier `pkgs.rustup`, which created linker
  # wrappers in ~/.rustup/.../bin/gcc-ld/ld.lld that hardcoded /nix/store paths
  # and broke under nix-collect-garbage. Stock rustup (installed by the developer
  # outside nix) is the unsupported fallback; the build scripts detect rustup at
  # runtime and run `rustup target add ...` only in that case.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  inputs.rust-overlay.url = "github:oxalica/rust-overlay";
  inputs.rust-overlay.inputs.nixpkgs.follows = "nixpkgs";

  outputs = { self, nixpkgs, rust-overlay }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "aarch64-darwin" "x86_64-darwin" ];
    in {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ (import rust-overlay) ];
          };
          rustToolchain = pkgs.rust-bin.stable.latest.default.override {
            targets = [
              "aarch64-linux-android"
              "x86_64-linux-android"
              "aarch64-apple-ios"
              "aarch64-apple-ios-sim"
              "x86_64-apple-ios"
            ];
          };
        in {
          default = pkgs.mkShellNoCC {
            packages = [ rustToolchain ];
          };
        });
    };
}
