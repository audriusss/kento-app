import { Router, type IRouter } from "express";
import healthRouter from "./health";
import markersRouter from "./markers";

const router: IRouter = Router();

router.use(healthRouter);
router.use(markersRouter);

export default router;
