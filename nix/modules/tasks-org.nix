self: {config, lib, pkgs, ...}: let
  cfg = config.programs.tasks-org;
in {
  options.programs.tasks-org = {
    enable = lib.mkEnableOption "Tasks.org desktop";
    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
      defaultText = lib.literalExpression "inputs.tasks-org.packages.\${pkgs.stdenv.hostPlatform.system}.default";
      description = "Tasks.org desktop package to install.";
    };
  };
  config = lib.mkIf cfg.enable {environment.systemPackages = [cfg.package];};
}
