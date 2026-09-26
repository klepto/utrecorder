import "@mantine/core/styles.css";
import { MantineProvider } from "@mantine/core";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Placeholder } from "./Placeholder";

const root = document.getElementById("root");
if (!root) throw new Error("Missing #root element");

createRoot(root).render(
  <StrictMode>
    <MantineProvider forceColorScheme="dark">
      <Placeholder />
    </MantineProvider>
  </StrictMode>,
);
