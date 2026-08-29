import { rsbuildConfig } from '@halo-dev/ui-plugin-bundler-kit';
import Icons from "unplugin-icons/rspack";
import { pluginSass } from "@rsbuild/plugin-sass";
import type { RsbuildConfig } from "@rsbuild/core";

export default rsbuildConfig({
  rsbuild: {
    resolve: {
      alias: {
        "@": "./src",
      },
    },
    output: {
      // Keep the GPL copyright notice (/*! headers in ui/src) in the minified
      // bundles shipped in the plugin JAR.
      legalComments: 'inline',
    },
    plugins: [pluginSass()],
    tools: {
      rspack: {
        plugins: [Icons({ compiler: "vue3" })],
      },
    },
  },
}) as RsbuildConfig
