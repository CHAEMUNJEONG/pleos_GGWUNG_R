pub mod coaching;
pub mod diagnostics;
pub mod eco_habits;
pub mod egg_levels;
pub mod energy;
pub mod energy_text;
pub mod engine;
pub mod fake_vehicle;
pub mod korea_outline;
pub mod league;
pub mod league_client;
pub mod models;
pub mod stamp_book;
pub mod stamp_name_resolver;
pub mod storage;
pub mod trip_continuation;
pub mod trip_recorder;

/// Exact Kotlin `Double.roundToInt()` / Java `Math.round()` / Python `math.floor(x + 0.5)` semantics:
/// rounds half towards +infinity.
#[inline]
pub fn round_half_up_i32(x: f64) -> i32 {
    (x + 0.5).floor() as i32
}
