# Timeline Visualizer 1.5.0

This release makes everyday routes calmer and easier to follow, especially when
the map is zoomed in on a commute.

## What changed

- The position marker can now move within a stable area near the center before
  the camera follows it. Small reversals no longer move the whole map immediately.
- Camera scale changes are softened to reduce distracting zoom changes as nearby
  route points enter and leave view.
- Preview, replay, seeking, and the finished MP4 now share the same preplanned
  camera movement.
- Long-distance trips still receive faster camera movement and remain visible.

The stabilization affects only the camera. Timeline locations, route lines,
dates, and distance calculations are not filtered or changed.

---

# 타임라인 비주얼라이저 1.5.0

이번 버전은 확대된 출퇴근 경로처럼 가까운 거리를 반복해서 이동할 때 지도를 더
편안하고 안정적으로 보여 줍니다.

## 달라진 점

- 위치 마커가 화면 중앙의 안정 영역 안에서 움직인 뒤 카메라가 따라갑니다. 이동
  방향이 조금 바뀔 때마다 지도 전체가 즉시 반대로 움직이지 않습니다.
- 주변 경로가 화면에 들어오고 나갈 때 확대 수준이 자주 바뀌지 않도록 카메라 배율
  변화를 부드럽게 조정했습니다.
- 미리보기, 다시 재생, 탐색, 완성된 MP4가 같은 카메라 움직임을 사용합니다.
- 장거리 이동에서는 카메라가 더 빠르게 따라가므로 중요한 여행 경로가 계속 보입니다.

안정화는 카메라에만 적용됩니다. 타임라인 위치, 경로선, 날짜, 이동 거리 계산은
필터링하거나 변경하지 않습니다.
