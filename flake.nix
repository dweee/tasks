{
  description = "Tasks.org desktop for Linux x86_64 and macOS Apple Silicon";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/7fd4004d0511991fff3a0524856a9be09e28c5f0";

  outputs = {self, nixpkgs}: let
    systems = ["x86_64-linux" "aarch64-darwin"];
    eachSystem = nixpkgs.lib.genAttrs systems;
    packageFor = pkgs: pkgs.callPackage ./nix/package.nix {};
  in {
    packages = eachSystem (system: let
      package = packageFor nixpkgs.legacyPackages.${system};
    in {default = package; tasks-org = package;});
    overlays.default = final: _: {tasks-org = packageFor final;};
    apps = eachSystem (system: {
      default = {type = "app"; program = "${self.packages.${system}.default}/bin/tasks-org";};
    });
    checks = eachSystem (system: {tasks-org = self.packages.${system}.default;});
    devShells = eachSystem (system: let pkgs = nixpkgs.legacyPackages.${system}; in {
      default = pkgs.mkShell {packages = [pkgs.jdk25 pkgs.git];};
    });
    nixosModules.default = self.nixosModules.tasks-org;
    nixosModules.tasks-org = import ./nix/modules/tasks-org.nix self;
    darwinModules.default = self.darwinModules.tasks-org;
    darwinModules.tasks-org = import ./nix/modules/tasks-org.nix self;
  };
}
