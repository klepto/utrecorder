import { Route, Routes } from "react-router";
import { Shell } from "./layout/Shell";
import { AboutPage } from "./pages/AboutPage";
import { HomePage } from "./pages/HomePage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { RecordingsPage } from "./pages/RecordingsPage";

/** The route table of the site, rendered inside whichever router the caller mounts. */
export const AppRoutes = () => {
  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={<HomePage />} />
        <Route path="recordings" element={<RecordingsPage />} />
        <Route path="about" element={<AboutPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
};
