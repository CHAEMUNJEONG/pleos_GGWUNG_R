use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::diagnostics::{Channel, ChannelState, DiagnosticsBoard};
use crate::energy::EnergySnapshot;
use crate::energy_text::{energy_text, format_grouped_one_decimal};
use crate::models::{GeoPoint, RoadType};
use crate::stamp_name_resolver::ArrivedPlace;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DriveState {
    Unknown,
    Parked,
    Driving,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpeedSample {
    pub time_ms: i64,
    pub speed_kmh: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SteeringSample {
    pub time_ms: i64,
    pub angle_deg: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrakeSample {
    pub time_ms: i64,
    pub pressed: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpeedLimitSample {
    pub time_ms: i64,
    pub limit_kmh: i32,
}

/// Output produced by one simulation tick (every 200ms).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FakeTickOutput {
    pub speed_sample: SpeedSample,
    pub steering_sample: SteeringSample,
    pub speed_limit_sample: SpeedLimitSample,
    pub brake_events: Vec<BrakeSample>,
    pub drive_state: DriveState,
    pub speed_kmh: f64,
    pub ux_restricted: bool,
    pub location: Option<GeoPoint>,
    pub address: Option<String>,
    pub address_updated_at_ms: Option<i64>,
    pub arrived_place: Option<ArrivedPlace>,
    pub diagnostics: BTreeMap<Channel, ChannelState>,
}

/// Action returned by `set_speed` so caller or tick loop emits brake samples.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FakeControlOutput {
    pub brake_events: Vec<BrakeSample>,
    pub drive_state: DriveState,
    pub speed_kmh: f64,
    pub ux_restricted: bool,
    pub location: Option<GeoPoint>,
    pub address: Option<String>,
    pub address_updated_at_ms: Option<i64>,
    pub arrived_place: Option<ArrivedPlace>,
    pub diagnostics: BTreeMap<Channel, ChannelState>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum DemoAction {
    Step { label: String },
    SetLocation {
        point: GeoPoint,
        name: String,
        address: String,
    },
    SetParked { parked: bool },
    SetSpeed { kmh: f64 },
    RampTo { target: f64, rate: f64 },
    Hold {
        #[serde(rename = "virtualSeconds")]
        virtual_seconds: i32,
    },
}

/// 시뮬레이션 차량과 시연 모드(5배속) 물리·상태 머신.
#[derive(Debug, Clone)]
pub struct FakeVehicleEngine {
    drive_state: DriveState,
    speed_kmh: f64,
    sim_limit: i32,
    steering_angle: f64,
    location: Option<GeoPoint>,
    destination_name: Option<String>,
    address: Option<String>,
    address_updated_at_ms: Option<i64>,
    arrived_place: Option<ArrivedPlace>,
    battery_wh: f64,
    odometer_km: f64,
    last_tick_ms: Option<i64>,
    last_tick_speed: f64,
    ux_restricted: bool,
    board: DiagnosticsBoard,
    time_scale: i64,
    anchor_real: i64,
    anchor_virtual: i64,
    demo_active: bool,
    brake_release_real_ms: Option<i64>,
    steer_reset_real_ms: Option<i64>,
}

impl FakeVehicleEngine {
    pub const DEMO_SPEED: i64 = 5;
    pub const BASE_WH_PER_KM: f64 = 140.0;
    pub const BRAKE_STEP_KMH: f64 = 10.0;
    pub const MASS_KG: f64 = 2_000.0;
    pub const LOSS_FACTOR: f64 = 0.8;

    pub fn new(initial_real_ms: i64, initial_wall_ms: i64) -> Self {
        let mut board = DiagnosticsBoard::new(1_000);
        board.ok(Channel::Gear, "P (주차) · 시뮬레이션", initial_wall_ms);
        board.ok(Channel::Ux, "제한 없음 · 시뮬레이션", initial_wall_ms);
        board.waiting(Channel::Location, "시뮬레이션 패널에서 목적지를 고르세요", initial_wall_ms);
        board.waiting(Channel::Arrival, "목적지를 고르고 주차하면 받아요", initial_wall_ms);
        board.waiting(Channel::Energy, "주행을 시작하면 읽어요", initial_wall_ms);
        board.waiting(
            Channel::Brake,
            "−30 버튼은 브레이크, 막대로 줄이면 발을 뗀 감속으로 흉내 내요",
            initial_wall_ms,
        );
        board.waiting(
            Channel::SpeedLimit,
            "속도에 맞춰 시내·국도·고속도로 제한속도를 흉내 내요",
            initial_wall_ms,
        );
        board.ok(
            Channel::Efficiency,
            "시뮬레이션에서는 배터리 잔량으로 계산해요",
            initial_wall_ms,
        );
        board.waiting(Channel::Trip, "주행을 시작하면 읽어요", initial_wall_ms);

        Self {
            drive_state: DriveState::Parked,
            speed_kmh: 0.0,
            sim_limit: 50,
            steering_angle: 0.0,
            location: None,
            destination_name: None,
            address: None,
            address_updated_at_ms: None,
            arrived_place: None,
            battery_wh: 72_000.0,
            odometer_km: 12_000.0,
            last_tick_ms: None,
            last_tick_speed: 0.0,
            ux_restricted: false,
            board,
            time_scale: 1,
            anchor_real: initial_real_ms,
            anchor_virtual: initial_real_ms,
            demo_active: false,
            brake_release_real_ms: None,
            steer_reset_real_ms: None,
        }
    }

    pub fn virtual_now(&self, real_ms: i64) -> i64 {
        self.anchor_virtual + (real_ms - self.anchor_real) * self.time_scale
    }

    pub fn set_time_scale(&mut self, scale: i64, real_ms: i64) {
        let virt = self.virtual_now(real_ms);
        self.anchor_real = real_ms;
        self.anchor_virtual = virt;
        self.time_scale = scale;
    }

    pub fn set_demo_active(&mut self, active: bool, real_ms: i64, wall_ms: i64) -> FakeControlOutput {
        if active {
            self.demo_active = true;
            self.set_time_scale(Self::DEMO_SPEED, real_ms);
            self.control_output(Vec::new())
        } else {
            if !self.demo_active {
                return self.control_output(Vec::new());
            }
            self.demo_active = false;
            self.set_time_scale(1, real_ms);
            if self.drive_state == DriveState::Driving {
                self.set_parked(true, wall_ms)
            } else {
                self.control_output(Vec::new())
            }
        }
    }

    pub fn tick(&mut self, real_ms: i64, wall_ms: i64) -> FakeTickOutput {
        let t = self.virtual_now(real_ms);
        let mut brake_events = Vec::new();
        if let Some(release_at) = self.brake_release_real_ms {
            if real_ms >= release_at {
                self.brake_release_real_ms = None;
                brake_events.push(BrakeSample {
                    time_ms: t,
                    pressed: false,
                });
                self.board.ok(Channel::Brake, "떼고 있음 · 시뮬레이션", wall_ms);
            }
        }
        if let Some(reset_at) = self.steer_reset_real_ms {
            if real_ms >= reset_at {
                self.steer_reset_real_ms = None;
                self.steering_angle = 0.0;
            }
        }

        let speed = self.speed_kmh;
        self.consume_energy(t, speed);
        let speed_limit = self.compute_speed_limit(t, speed, wall_ms);
        self.board.ok(
            Channel::Speed,
            format!("{:.0} km/h · 시뮬레이션", speed),
            wall_ms,
        );
        self.board.ok(
            Channel::Steering,
            format!("{:.0}° · 시뮬레이션", self.steering_angle),
            wall_ms,
        );

        FakeTickOutput {
            speed_sample: SpeedSample {
                time_ms: t,
                speed_kmh: speed,
            },
            steering_sample: SteeringSample {
                time_ms: t,
                angle_deg: self.steering_angle,
            },
            speed_limit_sample: speed_limit,
            brake_events,
            drive_state: self.drive_state,
            speed_kmh: self.speed_kmh,
            ux_restricted: self.ux_restricted,
            location: self.location,
            address: self.address.clone(),
            address_updated_at_ms: self.address_updated_at_ms,
            arrived_place: self.arrived_place.clone(),
            diagnostics: self.board.current(),
        }
    }

    fn consume_energy(&mut self, now_ms: i64, speed_kmh: f64) {
        let last = self.last_tick_ms;
        self.last_tick_ms = Some(now_ms);
        let before = self.last_tick_speed;
        self.last_tick_speed = speed_kmh;
        let Some(last_ms) = last else {
            return;
        };
        let dt_hours = (now_ms - last_ms) as f64 / 3_600_000.0;
        if dt_hours <= 0.0 || dt_hours > 5.0 / 3600.0 {
            return;
        }
        let km = (before + speed_kmh) / 2.0 * dt_hours;
        self.odometer_km += km;
        let mut wh = km * Self::BASE_WH_PER_KM;
        if speed_kmh > before {
            let v0 = before / 3.6;
            let v1 = speed_kmh / 3.6;
            wh += 0.5 * Self::MASS_KG * (v1 * v1 - v0 * v0) / 3600.0 * Self::LOSS_FACTOR;
        }
        self.battery_wh = (self.battery_wh - wh).max(0.0);
    }

    fn compute_speed_limit(&mut self, now: i64, speed: f64, wall_ms: i64) -> SpeedLimitSample {
        if speed >= 1.0 {
            self.sim_limit = if speed <= 50.0 {
                50
            } else if speed <= 90.0 {
                80
            } else {
                100
            };
        }
        self.board.ok(
            Channel::SpeedLimit,
            format!(
                "{} km/h · {} · 시뮬레이션",
                self.sim_limit,
                RoadType::of_limit(self.sim_limit).label()
            ),
            wall_ms,
        );
        SpeedLimitSample {
            time_ms: now,
            limit_kmh: self.sim_limit,
        }
    }

    pub fn read_energy(&mut self, wall_ms: i64) -> (EnergySnapshot, BTreeMap<Channel, ChannelState>) {
        let snap = EnergySnapshot {
            ev_battery_wh: Some(self.battery_wh),
            odometer_km: Some(self.odometer_km),
            ..EnergySnapshot::default()
        };
        self.board.ok(
            Channel::Energy,
            format!("{} · 시뮬레이션", energy_text(&snap)),
            wall_ms,
        );
        self.board.ok(
            Channel::Trip,
            format!(
                "누적 {} km · 시뮬레이션",
                format_grouped_one_decimal(self.odometer_km)
            ),
            wall_ms,
        );
        (snap, self.board.current())
    }

    pub fn set_speed(&mut self, kmh: f64, real_ms: i64, wall_ms: i64) -> FakeControlOutput {
        let target = kmh.clamp(0.0, 200.0);
        let current = self.speed_kmh;
        let mut brake_events = Vec::new();
        if target < current - Self::BRAKE_STEP_KMH {
            brake_events.push(BrakeSample {
                time_ms: self.virtual_now(real_ms),
                pressed: true,
            });
            self.board.ok(Channel::Brake, "밟는 중 · 시뮬레이션", wall_ms);
            self.brake_release_real_ms = Some(real_ms + 1_500);
        } else if target < current - 0.5 {
            brake_events.push(BrakeSample {
                time_ms: self.virtual_now(real_ms),
                pressed: false,
            });
        }
        self.speed_kmh = target;
        self.control_output(brake_events)
    }

    pub fn set_parked(&mut self, parked: bool, wall_ms: i64) -> FakeControlOutput {
        if parked {
            self.speed_kmh = 0.0;
            self.steering_angle = 0.0;
            if let Some(ref dest) = self.destination_name {
                self.arrived_place = Some(ArrivedPlace {
                    name: dest.clone(),
                    wall_time_ms: wall_ms,
                    point: self.location,
                });
                self.board
                    .ok(Channel::Arrival, format!("{dest} · 시뮬레이션"), wall_ms);
            }
        } else {
            self.arrived_place = None;
        }
        self.drive_state = if parked {
            DriveState::Parked
        } else {
            DriveState::Driving
        };
        self.board.ok(
            Channel::Gear,
            format!("{} · 시뮬레이션", if parked { "P (주차)" } else { "D (주행)" }),
            wall_ms,
        );
        self.ux_restricted = !parked;
        self.control_output(Vec::new())
    }

    pub fn set_location(
        &mut self,
        point: Option<GeoPoint>,
        name: Option<String>,
        address: Option<String>,
        wall_ms: i64,
    ) -> FakeControlOutput {
        self.location = point;
        self.destination_name = name.clone();
        self.address = address.clone();
        self.address_updated_at_ms = Some(wall_ms);
        if point.is_some() {
            let label = name
                .or(address)
                .unwrap_or_else(|| "선택한 위치".to_string());
            self.board
                .ok(Channel::Location, format!("{label} · 시뮬레이션"), wall_ms);
        }
        self.control_output(Vec::new())
    }

    pub fn jerk_steering(&mut self, real_ms: i64) -> FakeControlOutput {
        self.steering_angle = 120.0;
        self.steer_reset_real_ms = Some(real_ms + 600);
        self.control_output(Vec::new())
    }

    pub fn current_state(&self) -> FakeControlOutput {
        self.control_output(Vec::new())
    }

    pub fn demo_script() -> Vec<DemoAction> {
        vec![
            DemoAction::Step {
                label: "강릉 안목해변으로 출발".into(),
            },
            DemoAction::SetLocation {
                point: GeoPoint::new(37.7719, 128.9473),
                name: "강릉 안목해변".into(),
                address: "강원특별자치도 강릉시 창해로14번길".into(),
            },
            DemoAction::Hold { virtual_seconds: 2 },
            DemoAction::SetParked { parked: false },
            DemoAction::Hold { virtual_seconds: 3 },
            DemoAction::Step {
                label: "시내 · 부드럽게 출발".into(),
            },
            DemoAction::RampTo {
                target: 50.0,
                rate: 2.0,
            },
            DemoAction::Hold { virtual_seconds: 20 },
            DemoAction::Step {
                label: "급출발 한 번 (감점 음성)".into(),
            },
            DemoAction::SetSpeed { kmh: 80.0 },
            DemoAction::Hold { virtual_seconds: 3 },
            DemoAction::Step {
                label: "국도 · 정속 주행".into(),
            },
            DemoAction::Hold { virtual_seconds: 40 },
            DemoAction::Step {
                label: "고속도로 · 정속 주행".into(),
            },
            DemoAction::RampTo {
                target: 100.0,
                rate: 1.5,
            },
            DemoAction::Hold { virtual_seconds: 80 },
            DemoAction::Step {
                label: "고속도로 · 제한속도 넘겨 과속".into(),
            },
            DemoAction::RampTo {
                target: 125.0,
                rate: 2.0,
            },
            DemoAction::Hold { virtual_seconds: 25 },
            DemoAction::Step {
                label: "앞이 막혀 발을 떼고 미리 감속".into(),
            },
            DemoAction::RampTo {
                target: 40.0,
                rate: 2.5,
            },
            DemoAction::Step {
                label: "급정거 한 번 (감점 음성)".into(),
            },
            DemoAction::SetSpeed { kmh: 10.0 },
            DemoAction::Hold { virtual_seconds: 2 },
            DemoAction::RampTo {
                target: 0.0,
                rate: 3.0,
            },
            DemoAction::Step {
                label: "정체로 오래 정차 (공회전)".into(),
            },
            DemoAction::Hold { virtual_seconds: 170 },
            DemoAction::Step {
                label: "도착 · 주차".into(),
            },
            DemoAction::SetParked { parked: true },
            DemoAction::Hold { virtual_seconds: 2 },
        ]
    }

    fn control_output(&self, brake_events: Vec<BrakeSample>) -> FakeControlOutput {
        FakeControlOutput {
            brake_events,
            drive_state: self.drive_state,
            speed_kmh: self.speed_kmh,
            ux_restricted: self.ux_restricted,
            location: self.location,
            address: self.address.clone(),
            address_updated_at_ms: self.address_updated_at_ms,
            arrived_place: self.arrived_place.clone(),
            diagnostics: self.board.current(),
        }
    }
}
