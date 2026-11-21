use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// 차량에서 받는 데이터 종류.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Channel {
    Speed,
    SpeedLimit,
    Gear,
    Steering,
    Brake,
    Location,
    Arrival,
    Energy,
    Efficiency,
    Trip,
    Ux,
}

impl Channel {
    pub const ALL: [Channel; 11] = [
        Channel::Speed,
        Channel::SpeedLimit,
        Channel::Gear,
        Channel::Steering,
        Channel::Brake,
        Channel::Location,
        Channel::Arrival,
        Channel::Energy,
        Channel::Efficiency,
        Channel::Trip,
        Channel::Ux,
    ];

    pub fn label(self) -> &'static str {
        match self {
            Channel::Speed => "속도",
            Channel::SpeedLimit => "도로 제한속도",
            Channel::Gear => "기어 (P·D)",
            Channel::Steering => "핸들 각도",
            Channel::Brake => "브레이크 페달",
            Channel::Location => "현재 위치",
            Channel::Arrival => "목적지 도착",
            Channel::Energy => "연료·배터리",
            Channel::Efficiency => "차량 계산 연비·전비",
            Channel::Trip => "차량 트립 정보",
            Channel::Ux => "주행 중 화면 제한",
        }
    }

    pub fn effect(self) -> &'static str {
        match self {
            Channel::Speed => "점수와 주행 거리를 계산할 수 없어요",
            Channel::SpeedLimit => "도로 종류를 평균 속도로 추정하고, 시속 110km 를 넘을 때만 고속 주행으로 봐요",
            Channel::Gear => "속도로 출발·주차를 판단해요 (2분 넘게 멈추면 주차로 봐요)",
            Channel::Steering => "급조향은 빼고 급출발·급정거로만 점수를 매겨요",
            Channel::Brake => "페달 신호 없이 감속 정도만으로 급정거를 판단해요",
            Channel::Location => "여행 지도에 알을 놓을 수 없어요",
            Channel::Arrival => "알 이름을 목적지 대신 주소로 채워요",
            Channel::Energy => "실제 연비 대신 속도 패턴으로 추정한 에코 점수만 써요",
            Channel::Efficiency => "짧은 주행에서는 실제 연비를 재지 못할 수 있어요",
            Channel::Trip => "앱이 직접 잰 거리·시간만 써요",
            Channel::Ux => "차량의 주행 중 화면 제한 상태를 알 수 없어요",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ChannelStatus {
    Waiting,
    Ok,
    Unavailable,
}

/// 데이터 한 종류의 연결 상태.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelState {
    pub status: ChannelStatus,
    pub detail: String,
    #[serde(default)]
    pub updated_at_ms: Option<i64>,
}

/// 연결 점검판. 같은 상태의 잦은 갱신은 min_interval_ms 간격으로 제한한다.
#[derive(Debug, Clone)]
pub struct DiagnosticsBoard {
    min_interval_ms: i64,
    states: BTreeMap<Channel, ChannelState>,
}

impl Default for DiagnosticsBoard {
    fn default() -> Self {
        Self::new(1_000)
    }
}

impl DiagnosticsBoard {
    pub fn new(min_interval_ms: i64) -> Self {
        let mut states = BTreeMap::new();
        for ch in Channel::ALL {
            states.insert(
                ch,
                ChannelState {
                    status: ChannelStatus::Waiting,
                    detail: "확인 중".to_string(),
                    updated_at_ms: None,
                },
            );
        }
        Self {
            min_interval_ms,
            states,
        }
    }

    pub fn current(&self) -> BTreeMap<Channel, ChannelState> {
        self.states.clone()
    }

    pub fn ok(&mut self, channel: Channel, detail: impl Into<String>, now_ms: i64) -> bool {
        self.set(channel, ChannelStatus::Ok, detail.into(), now_ms)
    }

    pub fn waiting(&mut self, channel: Channel, detail: impl Into<String>, now_ms: i64) -> bool {
        self.set(channel, ChannelStatus::Waiting, detail.into(), now_ms)
    }

    pub fn unavailable(&mut self, channel: Channel, reason: impl Into<String>, now_ms: i64) -> bool {
        self.set(channel, ChannelStatus::Unavailable, reason.into(), now_ms)
    }

    fn set(&mut self, channel: Channel, status: ChannelStatus, detail: String, now_ms: i64) -> bool {
        let old = self.states.get(&channel).expect("all channels initialized");
        let same_status = old.status == status;
        if same_status && old.detail == detail {
            return false;
        }
        if same_status && status == ChannelStatus::Ok {
            if let Some(prev_ms) = old.updated_at_ms {
                if now_ms - prev_ms < self.min_interval_ms {
                    return false;
                }
            }
        }
        self.states.insert(
            channel,
            ChannelState {
                status,
                detail,
                updated_at_ms: Some(now_ms),
            },
        );
        true
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DriveMode {
    Unknown,
    Parked,
    Driving,
}

/// 기어 값을 받을 수 없을 때 속도로 주행 상태를 정한다.
#[derive(Debug, Clone)]
pub struct SpeedDriveDetector {
    start_kmh: f64,
    stop_kmh: f64,
    park_after_ms: i64,
    state: DriveMode,
    stopped_since_ms: Option<i64>,
    last_kmh: f64,
}

impl Default for SpeedDriveDetector {
    fn default() -> Self {
        Self::new(5.0, 1.0, 120_000)
    }
}

impl SpeedDriveDetector {
    pub fn new(start_kmh: f64, stop_kmh: f64, park_after_ms: i64) -> Self {
        Self {
            start_kmh,
            stop_kmh,
            park_after_ms,
            state: DriveMode::Unknown,
            stopped_since_ms: None,
            last_kmh: 0.0,
        }
    }

    pub fn on_speed(&mut self, time_ms: i64, kmh: f64) -> DriveMode {
        self.last_kmh = kmh;
        if kmh >= self.start_kmh {
            self.state = DriveMode::Driving;
            self.stopped_since_ms = None;
        } else if kmh <= self.stop_kmh {
            let since = *self.stopped_since_ms.get_or_insert(time_ms);
            if self.state != DriveMode::Parked && time_ms - since >= self.park_after_ms {
                self.state = DriveMode::Parked;
            }
        } else {
            self.stopped_since_ms = None;
        }
        self.state
    }

    pub fn on_tick(&mut self, time_ms: i64) -> DriveMode {
        self.on_speed(time_ms, self.last_kmh)
    }
}
