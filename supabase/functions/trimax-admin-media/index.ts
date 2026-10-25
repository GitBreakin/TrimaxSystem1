// Entry point of the trimax-admin-media Edge Function (the logic is in handler.ts, so it can be tested).
import { handle } from "./handler.ts";

Deno.serve(handle);
