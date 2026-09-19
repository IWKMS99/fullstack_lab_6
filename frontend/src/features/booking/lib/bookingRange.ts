import {MAX_BOOKING_HOURS, SLOT_INTERVAL_MINUTES, WORKING_DAY_END, WORKING_DAY_START} from './bookingConstants';
import {minutesToTime, timeToMinutes} from './timeSlots';
import {validateSlotRange} from './validateSlotRange';

interface BoundaryBaseOptions {
  boundaries: string[];
  selectedDate: Date;
  now: Date;
  daySlots: string[];
  availableSlots: Set<string>;
  maxHours?: number;
  workingDayStart?: string;
  workingDayEnd?: string;
}

interface ToBoundariesOptions extends BoundaryBaseOptions {
  from: string;
}

const isSameDay = (a: Date, b: Date) =>
  a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

export const buildSlotsFromRange = (from: string, to: string): string[] => {
  if (timeToMinutes(from) >= timeToMinutes(to)) {
    return [];
  }

  const slots: string[] = [];
  for (let minute = timeToMinutes(from); minute < timeToMinutes(to); minute += SLOT_INTERVAL_MINUTES) {
    slots.push(minutesToTime(minute));
  }

  return slots;
};

export const getDurationHours = (from: string, to: string): number => {
  return Math.max(0, (timeToMinutes(to) - timeToMinutes(from)) / 60);
};

export const getValidToBoundaries = ({
  from,
  boundaries,
  selectedDate,
  now,
  daySlots,
  availableSlots,
  maxHours = MAX_BOOKING_HOURS,
  workingDayStart = WORKING_DAY_START,
  workingDayEnd = WORKING_DAY_END,
}: ToBoundariesOptions): string[] => {
  const fromMinutes = timeToMinutes(from);

  if (isSameDay(selectedDate, now) && fromMinutes <= now.getHours() * 60 + now.getMinutes()) {
    return [];
  }

  return boundaries.filter((boundary) => {
    if (timeToMinutes(boundary) <= fromMinutes) {
      return false;
    }

    const range = buildSlotsFromRange(from, boundary);
    const validation = validateSlotRange(range, {
      daySlots,
      availableSlots,
      maxHours,
      workingDayStart,
      workingDayEnd,
    });

    return validation.ok;
  });
};

export const getValidFromBoundaries = ({
  boundaries,
  selectedDate,
  now,
  daySlots,
  availableSlots,
  maxHours = MAX_BOOKING_HOURS,
  workingDayStart = WORKING_DAY_START,
  workingDayEnd = WORKING_DAY_END,
}: BoundaryBaseOptions): string[] => {
  const nowMinutes = now.getHours() * 60 + now.getMinutes();

  return boundaries.filter((boundary) => {
    const boundaryMinutes = timeToMinutes(boundary);

    if (boundary === workingDayEnd) {
      return false;
    }

    if (isSameDay(selectedDate, now) && boundaryMinutes <= nowMinutes) {
      return false;
    }

    if (!availableSlots.has(boundary)) {
      return false;
    }

    const validTo = getValidToBoundaries({
      from: boundary,
      boundaries,
      selectedDate,
      now,
      daySlots,
      availableSlots,
      maxHours,
      workingDayStart,
      workingDayEnd,
    });

    return validTo.length > 0;
  });
};
