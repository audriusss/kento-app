import { Router, type IRouter } from "express";
import healthRouter from "./health";
import realtimeSessionRouter from "./realtimeSession";

const router: IRouter = Router();

router.use(healthRouter);
router.use(realtimeSessionRouter);

export default router;
