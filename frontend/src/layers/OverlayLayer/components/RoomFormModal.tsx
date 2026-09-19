import React from 'react';
import {zodResolver} from '@hookform/resolvers/zod';
import {Controller, useForm} from 'react-hook-form';
import {z} from 'zod';
import {useTranslation} from 'react-i18next';
import NumberStepperInput from '../../../components/ui/NumberStepperInput';

type RoomFormValues = {
  name: string;
  floor: number;
  capacity: number;
};

interface RoomFormModalProps {
  isOpen: boolean;
  mode: 'create' | 'edit';
  initialValues?: RoomFormValues;
  isPending?: boolean;
  errorMessage?: string;
  onClose: () => void;
  onSubmit: (values: RoomFormValues) => Promise<void> | void;
}

const DEFAULT_VALUES: RoomFormValues = {
  name: '',
  floor: 1,
  capacity: 1,
};

const RoomFormFields = ({
  mode,
  initialValues,
  isPending = false,
  errorMessage,
  onClose,
  onSubmit,
}: RoomFormModalProps) => {
  const {t} = useTranslation();
  const roomSchema = React.useMemo(
    () =>
      z.object({
        name: z.string().trim().min(1, t('admin.roomForm.validation.nameRequired')).max(255),
        floor: z.number().int().min(1, t('admin.roomForm.validation.floorMin')),
        capacity: z.number().int().min(1, t('admin.roomForm.validation.capacityMin')),
      }),
    [t]
  );

  const {
    register,
    control,
    handleSubmit,
    formState: {errors},
  } = useForm<RoomFormValues>({
    resolver: zodResolver(roomSchema),
    defaultValues: initialValues ?? DEFAULT_VALUES,
  });

  return (
    <div className="absolute inset-0 z-[120] flex items-center justify-center bg-black/60 p-4">
      <form
        onSubmit={handleSubmit((values) => void onSubmit(values))}
        className="w-full max-w-md rounded-2xl border border-white/20 bg-card/95 p-5"
      >
        <h4 className="m-0 text-lg font-semibold text-foreground">
          {mode === 'create' ? t('admin.roomForm.createTitle') : t('admin.roomForm.editTitle')}
        </h4>

        <div className="mt-4 grid gap-3">
          <label className="grid gap-1 text-xs text-muted-foreground">
            {t('admin.roomForm.name')}
            <input
              type="text"
              {...register('name')}
              className="rounded-lg border border-white/18 bg-background/50 px-3 py-2 text-sm text-foreground"
            />
            {errors.name && <span className="text-danger">{errors.name.message}</span>}
          </label>

          <label className="grid gap-1 text-xs text-muted-foreground">
            {t('admin.roomForm.floor')}
            <Controller
              control={control}
              name="floor"
              render={({field}) => (
                <NumberStepperInput
                  min={1}
                  value={field.value}
                  onValueChange={(next) => field.onChange(next ?? undefined)}
                  disabled={isPending}
                  inputClassName="py-2 text-sm"
                />
              )}
            />
            {errors.floor && <span className="text-danger">{errors.floor.message}</span>}
          </label>

          <label className="grid gap-1 text-xs text-muted-foreground">
            {t('admin.roomForm.capacity')}
            <Controller
              control={control}
              name="capacity"
              render={({field}) => (
                <NumberStepperInput
                  min={1}
                  value={field.value}
                  onValueChange={(next) => field.onChange(next ?? undefined)}
                  disabled={isPending}
                  inputClassName="py-2 text-sm"
                />
              )}
            />
            {errors.capacity && <span className="text-danger">{errors.capacity.message}</span>}
          </label>
        </div>

        {errorMessage && <p className="mt-3 text-xs text-danger">{errorMessage}</p>}

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-white/20 px-3 py-2 text-sm text-foreground"
            disabled={isPending}
          >
            {t('common.cancel')}
          </button>
          <button
            type="submit"
            className="rounded-lg border border-primary/45 bg-primary/25 px-3 py-2 text-sm font-semibold text-foreground"
            disabled={isPending}
          >
            {isPending ? t('common.saving') : mode === 'create' ? t('common.create') : t('common.save')}
          </button>
        </div>
      </form>
    </div>
  );
};

const RoomFormModal = (props: RoomFormModalProps) => {
  if (!props.isOpen) return null;
  // A new editing session gets defaults before input becomes interactive.
  // Equivalent values from a parent render must preserve the current draft.
  const {mode, initialValues} = props;
  const sessionKey = JSON.stringify([mode, initialValues?.name, initialValues?.floor, initialValues?.capacity]);
  return <RoomFormFields key={sessionKey} {...props} />;
};

export default RoomFormModal;
