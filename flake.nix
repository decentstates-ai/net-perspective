{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    {
        templates.default = {
          path = ./prsp-flake-template;
          description = "Ready-made environment for using prsp";
        };
    } //
    flake-utils.lib.eachDefaultSystem (system:
      let
        lib = nixpkgs.lib;
        pkgs = import nixpkgs { inherit system; };
        hugo-shibui-theme = pkgs.fetchFromGitHub {
          owner = "ntk148v";
          repo = "shibui";
          rev = "8d4a86ad2f9d9677e1d2bd535c41168a008feeb1";
          hash = "sha256-OVDn5csKMY8pWg3JvR/ojyIDlerd+EdZnfeHAUTVanc=";
        };
      in rec {
        packages.hugo-themes = pkgs.stdenv.mkDerivation {
          name = "hugo-themes";
          dontUnpack = true;
          installPhase = ''
            mkdir -p $out
            ln -s ${hugo-shibui-theme} $out/shibui
          '';
        };

        packages.hugo-with-themes = pkgs.symlinkJoin {
          name = "hugo-with-themes";
          paths = [
            pkgs.hugo
          ];
          
          buildInputs = [ pkgs.makeWrapper ];

          postBuild = ''
            wrapProgram $out/bin/hugo --set HUGO_themesDir '${self.packages.${system}.hugo-themes}'
          '';
        };

        packages."net-perspective-org-website" = pkgs.stdenv.mkDerivation {
          pname = "net-perspective-org-website";
          src = ./net-perspective.org;

          nativeBuildInputs = [
            # self.packages.${system}.hugo-with-themes
            pkgs.hugo
            # pkgs.git
          ];

          buildPhase = ''
            hugo
          '';

          installPhase = ''
            mkdir -p $out
            mv public $out
          '';
        };


        formatter = pkgs.nixfmt-rfc-style;
        devShells = {
          default = pkgs.mkShellNoCC {
            packages = [
              pkgs.hugo
              pkgs.go
              pkgs.kubo
              pkgs.clojure
              pkgs.jdk21
            ];
          };
        };
      });
}
