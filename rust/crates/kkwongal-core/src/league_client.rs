use std::time::Duration;

use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};
use url::Url;

use crate::league::{LeagueEntry, LeagueStanding};
use crate::models::TripResult;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TripUploadDto {
    pub trip_id: String,
    pub start_time_ms: i64,
    pub end_time_ms: i64,
    pub distance_km: f64,
    pub harsh_accel_count: i32,
    pub harsh_brake_count: i32,
    pub max_speed_kmh: f64,
    pub eco_score: i32,
    pub score: i32,
    pub harsh_steer_count: i32,
    pub simulated: bool,
    pub scoring_version: String,
}

impl TripUploadDto {
    pub const SCORING_VERSION: &'static str = "prototype-v2";

    /// Matches Java `UUID.nameUUIDFromBytes("${start}:${end}:${simulated}".toByteArray(UTF_8))`.
    pub fn stable_trip_id(start_time_ms: i64, end_time_ms: i64, simulated: bool) -> String {
        let identity = format!("{start_time_ms}:{end_time_ms}:{simulated}");
        let mut digest = Md5::digest(identity.as_bytes());
        digest[6] = (digest[6] & 0x0f) | 0x30; // version 3
        digest[8] = (digest[8] & 0x3f) | 0x80; // IETF variant
        format!(
            "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
            digest[0], digest[1], digest[2], digest[3],
            digest[4], digest[5],
            digest[6], digest[7],
            digest[8], digest[9],
            digest[10], digest[11], digest[12], digest[13], digest[14], digest[15]
        )
    }

    pub fn from_trip(trip: &TripResult) -> Self {
        Self {
            trip_id: Self::stable_trip_id(trip.start_time_ms, trip.end_time_ms, trip.simulated),
            start_time_ms: trip.start_time_ms,
            end_time_ms: trip.end_time_ms,
            distance_km: trip.distance_km,
            harsh_accel_count: trip.harsh_accel_count,
            harsh_brake_count: trip.harsh_brake_count,
            max_speed_kmh: trip.max_speed_kmh,
            eco_score: trip.eco_score,
            score: trip.score,
            harsh_steer_count: trip.harsh_steer_count,
            simulated: trip.simulated,
            scoring_version: Self::SCORING_VERSION.to_string(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TripReceiptDto {
    pub trip_id: Option<String>,
    pub accepted: Option<bool>,
    pub duplicate: Option<bool>,
    pub score: Option<i32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ApiFailure {
    Http,
    Network,
    InvalidResponse,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum UploadReply {
    Success {
        duplicate: bool,
    },
    Failed {
        reason: ApiFailure,
        #[serde(rename = "httpStatus")]
        http_status: Option<i32>,
    },
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeagueEntryDto {
    pub name: Option<String>,
    pub average_score: Option<f64>,
    pub is_me: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WeeklyLeagueDto {
    pub week_start_ms: Option<i64>,
    pub entries: Option<Vec<Option<LeagueEntryDto>>>,
    pub my_rank: Option<i32>,
    pub my_average: Option<f64>,
    pub my_league_score: Option<f64>,
    pub my_distance_km: Option<f64>,
    pub my_trip_count: Option<i32>,
    pub reward_points: Option<i32>,
    pub earned_points: Option<i32>,
    pub simulated: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeagueSnapshot {
    pub standing: LeagueStanding,
    pub earned_points: i32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum LeagueReply {
    Success {
        snapshot: LeagueSnapshot,
    },
    Failed {
        reason: ApiFailure,
        #[serde(rename = "httpStatus")]
        http_status: Option<i32>,
    },
}

#[derive(Debug, Clone)]
pub struct LeagueClient {
    base_url: String,
    token: Option<String>,
    agent: ureq::Agent,
}

impl LeagueClient {
    pub fn validate_base_url(base_url: &str, allow_local_http: bool) -> Result<String, String> {
        let url = Url::parse(base_url).map_err(|e| format!("Invalid URL: {e}"))?;
        let scheme = url.scheme();
        if scheme != "http" && scheme != "https" {
            return Err("HTTPS URL required".to_string());
        }
        let host = url.host_str().unwrap_or("");
        let is_https = scheme == "https";
        let local_http = allow_local_http
            && scheme == "http"
            && matches!(host, "localhost" | "127.0.0.1" | "10.0.2.2");
        if !(is_https || local_http) || !url.username().is_empty() || url.password().is_some() {
            return Err("HTTPS URL required".to_string());
        }
        if url.query().is_some() || url.fragment().is_some() || !url.path().ends_with('/') {
            return Err("API base URL must end with / and contain no query/fragment".to_string());
        }
        Ok(url.to_string())
    }

    pub fn create(
        base_url: &str,
        token: Option<String>,
        allow_local_http: bool,
    ) -> Result<Self, String> {
        let validated = Self::validate_base_url(base_url, allow_local_http)?;
        let clean_token = token.filter(|t| !t.trim().is_empty());
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(10))
            .timeout_read(Duration::from_secs(10))
            .timeout(Duration::from_secs(15))
            .redirects(0)
            .build();
        Ok(Self {
            base_url: validated,
            token: clean_token,
            agent,
        })
    }

    pub fn submit(&self, trip: &TripUploadDto) -> UploadReply {
        let endpoint = format!("{}v1/trips", self.base_url);
        let mut req = self
            .agent
            .post(&endpoint)
            .set("Accept", "application/json")
            .set("Content-Type", "application/json");
        if let Some(ref tok) = self.token {
            req = req.set("Authorization", &format!("Bearer {tok}"));
        }
        let payload = match serde_json::to_string(trip) {
            Ok(s) => s,
            Err(_) => {
                return UploadReply::Failed {
                    reason: ApiFailure::InvalidResponse,
                    http_status: None,
                }
            }
        };
        match req.send_string(&payload) {
            Ok(resp) => {
                let status = resp.status();
                if !(200..300).contains(&status) {
                    return UploadReply::Failed {
                        reason: ApiFailure::Http,
                        http_status: Some(status as i32),
                    };
                }
                let body = match resp.into_string() {
                    Ok(b) => b,
                    Err(_) => {
                        return UploadReply::Failed {
                            reason: ApiFailure::InvalidResponse,
                            http_status: None,
                        }
                    }
                };
                Self::validate_receipt(trip, &body)
            }
            Err(ureq::Error::Status(code, _)) => UploadReply::Failed {
                reason: ApiFailure::Http,
                http_status: Some(code as i32),
            },
            Err(ureq::Error::Transport(_)) => UploadReply::Failed {
                reason: ApiFailure::Network,
                http_status: None,
            },
        }
    }

    pub fn weekly(&self, week_start_ms: i64, simulated: bool) -> LeagueReply {
        let endpoint = format!(
            "{}v1/league/weekly?weekStartMs={}&simulated={}",
            self.base_url,
            week_start_ms,
            if simulated { "true" } else { "false" }
        );
        let mut req = self.agent.get(&endpoint).set("Accept", "application/json");
        if let Some(ref tok) = self.token {
            req = req.set("Authorization", &format!("Bearer {tok}"));
        }
        match req.call() {
            Ok(resp) => {
                let status = resp.status();
                if !(200..300).contains(&status) {
                    return LeagueReply::Failed {
                        reason: ApiFailure::Http,
                        http_status: Some(status as i32),
                    };
                }
                let body = match resp.into_string() {
                    Ok(b) => b,
                    Err(_) => {
                        return LeagueReply::Failed {
                            reason: ApiFailure::InvalidResponse,
                            http_status: None,
                        }
                    }
                };
                Self::validate_weekly(&body, week_start_ms, simulated)
            }
            Err(ureq::Error::Status(code, _)) => LeagueReply::Failed {
                reason: ApiFailure::Http,
                http_status: Some(code as i32),
            },
            Err(ureq::Error::Transport(_)) => LeagueReply::Failed {
                reason: ApiFailure::Network,
                http_status: None,
            },
        }
    }

    pub fn validate_receipt(trip: &TripUploadDto, body: &str) -> UploadReply {
        if body.trim().is_empty() {
            return UploadReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None,
            };
        }
        let Ok(receipt) = serde_json::from_str::<TripReceiptDto>(body) else {
            return UploadReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None,
            };
        };
        if receipt.trip_id.as_deref() != Some(trip.trip_id.as_str())
            || receipt.accepted != Some(true)
            || receipt.score != Some(trip.score)
        {
            return UploadReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None,
            };
        }
        match receipt.duplicate {
            Some(dup) => UploadReply::Success { duplicate: dup },
            None => UploadReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None,
            },
        }
    }

    pub fn validate_weekly(
        body: &str,
        requested_week: i64,
        requested_simulation: bool,
    ) -> LeagueReply {
        if body.trim().is_empty() {
            return LeagueReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None,
            };
        }
        let Ok(dto) = serde_json::from_str::<WeeklyLeagueDto>(body) else {
            return LeagueReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None,
            };
        };
        match Self::check_weekly(dto, requested_week, requested_simulation) {
            Some(snapshot) => LeagueReply::Success { snapshot },
            None => LeagueReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None,
            },
        }
    }

    fn check_weekly(
        dto: WeeklyLeagueDto,
        requested_week: i64,
        requested_simulation: bool,
    ) -> Option<LeagueSnapshot> {
        if dto.week_start_ms != Some(requested_week) || dto.simulated != Some(requested_simulation)
        {
            return None;
        }
        let raw_entries = dto.entries?;
        let mut rows = Vec::with_capacity(raw_entries.len());
        for raw in raw_entries {
            let row = raw?;
            let name = row.name?.trim().to_string();
            let score = row.average_score?;
            if name.is_empty()
                || name.chars().count() > 100
                || !score.is_finite()
                || !(0.0..=100.0).contains(&score)
            {
                return None;
            }
            rows.push(LeagueEntry {
                name,
                average_score: score,
                is_me: row.is_me?,
            });
        }
        if rows.len() > 1000 {
            return None;
        }
        if !rows.windows(2).all(|w| w[0].average_score >= w[1].average_score) {
            return None;
        }
        if rows.iter().filter(|r| r.is_me).count() > 1 {
            return None;
        }
        let expected_rank = rows.iter().position(|r| r.is_me).map(|i| (i + 1) as i32);
        if dto.my_rank != expected_rank {
            return None;
        }
        let km = dto.my_distance_km?;
        let count = dto.my_trip_count?;
        let reward = dto.reward_points?;
        let earned = dto.earned_points?;
        if !km.is_finite() || km < 0.0 || count < 0 || reward < 0 || earned < 0 {
            return None;
        }
        if let Some(avg) = dto.my_average {
            if !avg.is_finite() || !(0.0..=100.0).contains(&avg) {
                return None;
            }
        }
        if let Some(ls) = dto.my_league_score {
            if !ls.is_finite() || !(0.0..=100.0).contains(&ls) {
                return None;
            }
        }
        match dto.my_rank {
            None => {
                if dto.my_average.is_some()
                    || dto.my_league_score.is_some()
                    || count != 0
                    || km != 0.0
                    || reward != 0
                {
                    return None;
                }
            }
            Some(rank) => {
                let row = rows.get((rank - 1) as usize)?;
                if dto.my_league_score != Some(row.average_score)
                    || dto.my_average.is_none()
                    || count <= 0
                    || km < count as f64
                {
                    return None;
                }
            }
        }
        Some(LeagueSnapshot {
            standing: LeagueStanding {
                week_start_ms: requested_week,
                entries: rows,
                my_rank: dto.my_rank,
                my_average: dto.my_average,
                my_league_score: dto.my_league_score,
                my_distance_km: km,
                my_trip_count: count,
                reward_points: reward,
            },
            earned_points: earned,
        })
    }
}
