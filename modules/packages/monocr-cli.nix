{
  perSystem =
    {
      config,
      pkgs,
      lib,
      ...
    }:
    {
      packages.default = config.packages.monocr-cli;
      packages.monocr-cli = pkgs.rustPlatform.buildRustPackage {
        pname = "monocr-cli";
        version = (builtins.fromTOML (builtins.readFile ../../apps/cli/Cargo.toml)).package.version;
        src = lib.cleanSource ../../apps/cli;
        cargoLock.lockFile = ../../apps/cli/Cargo.lock;

        nativeBuildInputs = [
          pkgs.makeWrapper
          pkgs.pkg-config
        ];
        buildInputs = [
          pkgs.onnxruntime
          pkgs.openssl
        ];

        # Use the Nix runtime instead of ort's build-time binary download.
        ORT_LIB_LOCATION = "${lib.getLib pkgs.onnxruntime}/lib";
        ORT_PREFER_DYNAMIC_LINK = "1";

        nativeCheckInputs = [ pkgs.poppler-utils ];
        MONOCR_PDF_FIXTURE = ../../samples/buddha-chronicle/input.pdf;
        REQUIRE_E2E = "1";

        postFixup = ''
          wrapProgram $out/bin/monocr-cli \
            --prefix PATH : ${lib.makeBinPath [ pkgs.poppler-utils ]}
        '';

        doInstallCheck = true;
        installCheckPhase = ''
          runHook preInstallCheck
          $out/bin/monocr-cli --help
          $out/bin/monocr-cli inspect "$MONOCR_PDF_FIXTURE"
          runHook postInstallCheck
        '';

        meta = {
          description = "Batch Mon OCR over books, PDFs and images, on-device";
          homepage = "https://github.com/MonDevHub/monocr";
          license = lib.licenses.mit;
          mainProgram = "monocr-cli";
          platforms = lib.platforms.linux ++ lib.platforms.darwin;
        };
      };
    };
}
