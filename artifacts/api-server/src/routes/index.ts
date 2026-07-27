import { Router, type IRouter } from "express";
import healthRouter from "./health.js";
import markersRouter from "./markers.js";
import transcribeRouter from "./transcribe.js";
import chatRouter from "./chat.js";

const router: IRouter = Router();

router.use(healthRouter);
router.use(markersRouter);
router.use(transcribeRouter);
router.use(chatRouter);

export default router;
