use std::collections::BTreeSet;
use std::str::FromStr;

use chrono::{Datelike, Duration, FixedOffset, NaiveTime, TimeZone};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};

use crate::models::TripResult;
use crate::round_half_up_i32;

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeagueConfig {
    pub rival_count: usize,
    pub min_weekly_km: f64,
    pub min_trip_km: f64,
    pub prior_score: f64,
    pub prior_km: f64,
    pub top_tier_points: i32,
    pub mid_tier_points: i32,
    pub participation_points: i32,
}

impl Default for LeagueConfig {
    fn default() -> Self {
        Self {
            rival_count: 19,
            min_weekly_km: 1.0,
            min_trip_km: 1.0,
            prior_score: 80.0,
            prior_km: 20.0,
            top_tier_points: 500,
            mid_tier_points: 200,
            participation_points: 50,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeagueEntry {
    pub name: String,
    pub average_score: f64,
    pub is_me: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeagueStanding {
    pub week_start_ms: i64,
    pub entries: Vec<LeagueEntry>,
    #[serde(default)]
    pub my_rank: Option<i32>,
    #[serde(default)]
    pub my_average: Option<f64>,
    #[serde(default)]
    pub my_league_score: Option<f64>,
    pub my_distance_km: f64,
    pub my_trip_count: i32,
    pub reward_points: i32,
}

/// Exact bit-for-bit implementation of Kotlin stdlib `kotlin.random.XorWowRandom`
/// so virtual weekly rivals match the original Kotlin app identically.
struct KotlinXorWowRandom {
    x: u32,
    y: u32,
    z: u32,
    w: u32,
    v: u32,
    addend: u32,
}

impl KotlinXorWowRandom {
    fn from_seed(seed: i64) -> Self {
        let seed1 = seed as u32;
        let seed2 = ((seed as u64) >> 32) as u32;
        let mut rng = Self {
            x: seed1,
            y: seed2,
            z: 0,
            w: 0,
            v: !seed1,
            addend: (seed1 << 10) ^ (seed2 >> 4),
        };
        for _ in 0..64 {
            rng.next_u32();
        }
        rng
    }

    fn next_u32(&mut self) -> u32 {
        let mut t = self.x;
        t ^= t >> 2;
        self.x = self.y;
        self.y = self.z;
        self.z = self.w;
        let v0 = self.v;
        self.w = v0;
        t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4);
        self.v = t;
        self.addend = self.addend.wrapping_add(362_437);
        t.wrapping_add(self.addend)
    }

    fn next_bits(&mut self, bit_count: u32) -> u32 {
        if bit_count == 0 {
            return 0;
        }
        self.next_u32() >> (32 - bit_count)
    }

    fn next_double(&mut self) -> f64 {
        let hi = self.next_bits(26) as u64;
        let lo = self.next_bits(27) as u64;
        ((hi << 27) + lo) as f64 / ((1u64 << 53) as f64)
    }
}

pub struct League;

impl League {
    const RIVAL_NAMES: [&'static str; 20] = [
        "부드러운코너",
        "새벽드라이버",
        "에코마스터",
        "한강러너",
        "느긋한출근길",
        "주말탐험가",
        "정속주행",
        "초록신호",
        "바람따라",
        "동해일출",
        "남도여행",
        "첫차타는사람",
        "조용한엔진",
        "노을길",
        "고속도로산책",
        "안전제일",
        "커피한잔",
        "별보는밤",
        "제주한바퀴",
        "골목대장",
    ];

    /// 그 시각이 속한 주의 시작(월요일 0시) 시각 (IANA zone name, e.g. "Asia/Seoul").
    pub fn week_start_ms(time_ms: i64, zone_name: &str) -> i64 {
        if let Ok(tz) = Tz::from_str(zone_name) {
            let dt = tz
                .timestamp_millis_opt(time_ms)
                .single()
                .expect("valid timestamp");
            let days_from_mon = dt.weekday().num_days_from_monday() as i64;
            let monday = dt.date_naive() - Duration::days(days_from_mon);
            let midnight = monday.and_time(NaiveTime::MIN);
            // Match java.time LocalDate.atStartOfDay(zone) (earliest valid local time)
            let local_dt = tz
                .from_local_datetime(&midnight)
                .earliest()
                .unwrap_or_else(|| {
                    tz.timestamp_millis_opt(time_ms - days_from_mon * 86_400_000)
                        .single()
                        .unwrap()
                });
            return local_dt.timestamp_millis();
        }
        // Fallback: KST (+09:00)
        Self::week_start_ms_kst(time_ms)
    }

    /// KST(+09:00) 월요일 0시 시각 (backend/server.py 및 기본 앱 타임존과 100% 동일).
    pub fn week_start_ms_kst(time_ms: i64) -> i64 {
        let kst = FixedOffset::east_opt(9 * 3600).unwrap();
        let dt = kst
            .timestamp_millis_opt(time_ms)
            .single()
            .expect("valid timestamp");
        let days_from_mon = dt.weekday().num_days_from_monday() as i64;
        let monday = dt.date_naive() - Duration::days(days_from_mon);
        let midnight = monday.and_time(NaiveTime::MIN);
        kst.from_local_datetime(&midnight)
            .single()
            .unwrap()
            .timestamp_millis()
    }

    /// 순위에 따른 리워드.
    pub fn reward_for(rank: i32, size: i32, config: &LeagueConfig) -> i32 {
        let top_cutoff = (size as f64 * 0.1).ceil() as i32;
        let mid_cutoff = (size as f64 * 0.3).ceil() as i32;
        if rank <= top_cutoff {
            config.top_tier_points
        } else if rank <= mid_cutoff {
            config.mid_tier_points
        } else {
            config.participation_points
        }
    }

    fn rivals(week_start_ms: i64, config: &LeagueConfig) -> Vec<LeagueEntry> {
        let mut rng = KotlinXorWowRandom::from_seed(week_start_ms / 86_400_000);
        (0..config.rival_count)
            .map(|index| {
                let base = Self::RIVAL_NAMES[index % Self::RIVAL_NAMES.len()];
                let name = if index >= Self::RIVAL_NAMES.len() {
                    format!("{base}{}", index / Self::RIVAL_NAMES.len() + 1)
                } else {
                    base.to_string()
                };
                let raw = (62.0 + rng.next_double() * 34.0) * 10.0;
                let score = round_half_up_i32(raw) as f64 / 10.0;
                LeagueEntry {
                    name,
                    average_score: score,
                    is_me: false,
                }
            })
            .collect()
    }

    /// 리그에 넣는 기록: 같은 모드이고 min_trip_km 이상 달린 주행.
    pub fn eligible<'a>(
        trips: &'a [TripResult],
        simulated: bool,
        config: &LeagueConfig,
    ) -> Vec<&'a TripResult> {
        trips
            .iter()
            .filter(|t| t.simulated == simulated && t.distance_km >= config.min_trip_km)
            .collect()
    }

    /// now_ms 가 속한 주의 순위표.
    pub fn standing(
        trips: &[TripResult],
        now_ms: i64,
        zone_name: &str,
        config: &LeagueConfig,
        simulated: bool,
    ) -> LeagueStanding {
        let week_start = Self::week_start_ms(now_ms, zone_name);
        let mine: Vec<&TripResult> = Self::eligible(trips, simulated, config)
            .into_iter()
            .filter(|t| Self::week_start_ms(t.end_time_ms, zone_name) == week_start)
            .collect();
        let distance: f64 = mine.iter().map(|t| t.distance_km).sum();
        let qualified = !mine.is_empty() && distance >= config.min_weekly_km;
        let weighted: f64 = mine.iter().map(|t| t.score as f64 * t.distance_km).sum();
        let average = if mine.is_empty() {
            None
        } else if distance > 0.0 {
            Some(weighted / distance)
        } else {
            Some(mine.iter().map(|t| t.score as f64).sum::<f64>() / mine.len() as f64)
        };
        let league_score = if mine.is_empty() {
            None
        } else {
            let shrunk = (weighted + config.prior_score * config.prior_km)
                / (distance + config.prior_km);
            Some(round_half_up_i32(shrunk * 10.0) as f64 / 10.0)
        };

        let mut entries = Self::rivals(week_start, config);
        if qualified {
            entries.push(LeagueEntry {
                name: "나".to_string(),
                average_score: league_score.unwrap(),
                is_me: true,
            });
        }
        // Sort descending by average_score, then descending by is_me (true before false).
        // Rust's sort is stable, matching Kotlin's sortedWith.
        entries.sort_by(|a, b| {
            b.average_score
                .total_cmp(&a.average_score)
                .then_with(|| b.is_me.cmp(&a.is_me))
        });

        let my_rank = entries
            .iter()
            .position(|e| e.is_me)
            .map(|idx| (idx + 1) as i32);
        let reward_points = my_rank
            .map(|r| Self::reward_for(r, entries.len() as i32, config))
            .unwrap_or(0);

        LeagueStanding {
            week_start_ms: week_start,
            entries,
            my_rank,
            my_average: average,
            my_league_score: league_score,
            my_distance_km: distance,
            my_trip_count: mine.len() as i32,
            reward_points,
        }
    }

    /// 이미 끝난 주들에서 받은 포인트 합계.
    pub fn earned_points(
        trips: &[TripResult],
        now_ms: i64,
        zone_name: &str,
        config: &LeagueConfig,
        simulated: bool,
    ) -> i32 {
        let current_week = Self::week_start_ms(now_ms, zone_name);
        let past_weeks: BTreeSet<i64> = Self::eligible(trips, simulated, config)
            .into_iter()
            .map(|t| Self::week_start_ms(t.end_time_ms, zone_name))
            .filter(|&w| w < current_week)
            .collect();
        past_weeks
            .into_iter()
            .map(|w| Self::standing(trips, w, zone_name, config, simulated).reward_points)
            .sum()
    }
}
