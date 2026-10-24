// SPDX-License-Identifier: Apache-2.0
import { useLayoutEffect, useState, type RefObject, type CSSProperties } from 'react';
import { usePageEnvironment } from './page-context.js';
interface Point {
  x: number;
  y: number;
}
interface Frame {
  left: number;
  top: number;
  width: number;
  height: number;
}
/** Fixed descendants of a layout-contained page use page-local coordinates. */
export function menuPosition(point: Point, frame: Frame, size: { width: number; height: number }) {
  return {
    left: Math.max(0, Math.min(point.x - frame.left, frame.width - size.width)),
    top: Math.max(0, Math.min(point.y - frame.top, frame.height - size.height)),
    maxWidth: frame.width,
    maxHeight: frame.height,
  };
}
export function useMenuPosition(
  point: Point | null,
  ref: RefObject<HTMLDivElement | null>
): CSSProperties {
  const page = usePageEnvironment();
  const [position, setPosition] = useState<CSSProperties>({});
  useLayoutEffect(() => {
    if (!point) return;
    const update = () => {
      const frame = page?.root.current?.getBoundingClientRect() ?? {
        left: 0,
        top: 0,
        width: window.innerWidth,
        height: window.innerHeight,
      };
      const size = ref.current?.getBoundingClientRect() ?? { width: 0, height: 0 };
      const next = menuPosition(point, frame, size);
      setPosition((previous) =>
        previous.left === next.left &&
        previous.top === next.top &&
        previous.maxWidth === next.maxWidth &&
        previous.maxHeight === next.maxHeight
          ? previous
          : next
      );
    };
    update();
    const observer = new ResizeObserver(update);
    if (page?.root.current) observer.observe(page.root.current);
    if (ref.current) observer.observe(ref.current);
    window.addEventListener('resize', update);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', update);
    };
  }, [point, page, ref]);
  return { ...position, overflow: 'auto' };
}
