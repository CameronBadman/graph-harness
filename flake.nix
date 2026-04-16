{
  description = "GraphHarness prototype";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.jdk21;
        gradle = pkgs.gradle_8;
        kotlin = pkgs.kotlin;
        python = pkgs.python3;
      in
      {
        packages.default = pkgs.stdenv.mkDerivation {
          pname = "graphharness";
          version = "0.1.0";
          src = ./.;
          nativeBuildInputs = [ jdk kotlin pkgs.makeWrapper ];
          buildPhase = ''
            kotlinc src/main/kotlin/graphharness/*.kt -include-runtime -d graphharness.jar
          '';
          installPhase = ''
            mkdir -p $out/lib $out/bin
            cp graphharness.jar $out/lib/graphharness.jar
            makeWrapper ${jdk}/bin/java $out/bin/graphharness \
              --add-flags "-XX:+PerfDisableSharedMem -jar $out/lib/graphharness.jar"
          '';
        };

        apps.default = flake-utils.lib.mkApp {
          drv = self.packages.${system}.default;
          exePath = "/bin/graphharness";
        };

        devShells.default = pkgs.mkShell {
          packages = [
            jdk
            kotlin
            gradle
            python
          ];
        };
      });
}
