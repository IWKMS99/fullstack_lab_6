import {test, expect, type Page, type APIRequestContext} from '@playwright/test';
import {createHash} from 'node:crypto';

const admin = {email: 'admin@e2e.test', password: 'Test-Admin-Password-2026'};
const stub = process.env.E2E_HOLIDAY_URL || 'http://127.0.0.1:19084';
let adminToken = '';
const headers = () => ({Authorization: `Bearer ${adminToken}`});

async function login(page: Page, credentials = admin) {
  await page.goto('/login');
  await page.locator('#email').fill(credentials.email);
  await page.locator('#password').fill(credentials.password);
  await page.getByRole('button', {name: 'Войти', exact: true}).click();
  await expect(page).toHaveURL(/\/schedule$/);
}

async function createRoom(request: APIRequestContext, name: string, floor = 7, capacity = 12) {
  const response = await request.post('/api/v1/admin/rooms', {headers: headers(), data: {name, floor, capacity}});
  expect(response.status()).toBe(201);
  return await response.json() as {id: string; name: string};
}

test.describe.serial('real browser, API, PostgreSQL and private S3', () => {
  test.beforeAll(async ({request}) => {
    const response = await request.post('/api/v1/auth/login', {data: admin});
    expect(response.status()).toBe(200);
    adminToken = (await response.json()).token;
  });

  test('upstream outage is visible and recovery restores calendar without disabling schedule', async ({page, request}) => {
    await request.post(`${stub}/__admin/mode`, {data: {mode: 'error'}});
    const room = await createRoom(request, 'E2E Calendar');
    try {
      await page.goto(`/schedule/room/${room.id}`);
      await expect(page.getByRole('alert')).toContainText('Календарь праздников временно недоступен');
      await expect(page.getByText(room.name, {exact: true}).first()).toBeVisible();
      expect((await request.get('/api/v1/schedule?date=2030-01-02')).status()).toBe(200);
      await request.post(`${stub}/__admin/mode`, {data: {mode: 'ok'}});
      const retryResponse = page.waitForResponse(response => response.url().includes('/holidays') && response.status() === 200);
      await page.getByRole('button', {name: 'Повторить загрузку'}).click();
      await retryResponse;
      await expect(page.getByRole('alert')).not.toBeVisible();
      const holidays = await request.get(`/api/v1/holidays?year=${new Date().getFullYear()}&country=RU`);
      expect((await holidays.json())[0].date).toMatch(/-01-01$/);
      await expect.poll(async () => (await request.get('/api/v1/holidays?year=2092&country=GB')).status(), {timeout: 45000, intervals: [1000]}).toBe(200);
    } finally {
      await request.post(`${stub}/__admin/mode`, {data: {mode: 'ok'}});
      await request.delete(`/api/v1/admin/rooms/${room.id}`, {headers: headers()});
    }
  });

  test('UI login restores session through refresh cookie and logout revokes it', async ({page, context}) => {
    await login(page);
    const originalToken = await page.evaluate(() => localStorage.getItem('authToken'));
    const cookie = (await context.cookies()).find(item => item.name === 'refresh_token');
    expect(cookie?.httpOnly).toBe(true);
    await page.evaluate(() => localStorage.removeItem('authToken'));
    const refreshed = page.waitForResponse(response => response.url().endsWith('/auth/refresh') && response.status() === 200);
    await page.reload();
    await refreshed;
    await expect.poll(() => page.evaluate(() => localStorage.getItem('authToken'))).not.toBeNull();
    expect(originalToken).not.toBeNull();
    await page.goto('/admin');
    await expect(page.getByRole('button', {name: 'Создать комнату', exact: true})).toBeVisible();
    await page.getByRole('button', {name: 'Профиль', exact: true}).click();
    await page.getByRole('button', {name: /Выйти/}).click();
    await expect.poll(() => page.evaluate(() => localStorage.getItem('authToken'))).toBeNull();
    expect((await context.request.post('/api/v1/auth/refresh')).status()).toBe(401);
    await page.goto('/admin');
    await expect(page).toHaveURL(/\/login\?redirect/);
  });

  test('admin creates edits and deletes a room through UI while regular role is forbidden', async ({page, request, browser}) => {
    const suffix = Date.now().toString();
    const name = `E2E CRUD ${suffix}`;
    await login(page);
    await page.goto('/admin');
    await page.getByRole('button', {name: 'Создать комнату', exact: true}).click();
    const form = page.locator('form');
    await form.getByRole('textbox', {name: 'Название'}).fill(name);
    await expect(form.getByRole('textbox', {name: 'Название'})).toHaveValue(name);
    const createdResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/admin/rooms') && response.request().method() === 'POST');
    await form.getByRole('button', {name: 'Создать', exact: true}).click();
    const created = await createdResponse;
    expect(created.status()).toBe(201);
    const id = (await created.json()).id;
    try {
      await expect(form).not.toBeVisible();
      await page.getByPlaceholder('Поиск по названию').fill(name);
      const row = page.getByRole('row').filter({hasText: name});
      await expect(row).toBeVisible();
      await row.getByRole('button', {name: 'Редактировать', exact: true}).click();
      await form.getByRole('textbox', {name: 'Название'}).fill(`${name} updated`);
      const updated = page.waitForResponse(response => response.url().endsWith(`/api/v1/admin/rooms/${id}`) && response.request().method() === 'PUT');
      await form.getByRole('button', {name: 'Сохранить', exact: true}).click();
      expect((await updated).status()).toBe(200);
      await expect(form).not.toBeVisible();
      await expect(row).toContainText('updated');

      const regular = await browser.newContext({baseURL: page.url().split('/admin')[0]});
      try {
        const credentials = {email: `member-${suffix}@e2e.test`, password: 'Test-Member-Password-2026'};
        const registered = await regular.request.post('/api/v1/auth/register', {data: credentials});
        expect(registered.status()).toBe(200);
        const token = (await registered.json()).token;
        for (const method of ['post', 'put', 'delete'] as const) {
          const response = await regular.request[method](`/api/v1/admin/rooms${method === 'post' ? '' : `/${id}`}`, {headers: {Authorization: `Bearer ${token}`}, data: {name: 'Forbidden', floor: 1, capacity: 1}});
          expect(response.status()).toBe(403);
        }
        const regularPage = await regular.newPage();
        await regular.clearCookies();
        await login(regularPage, credentials);
        await regularPage.goto('/admin');
        await expect(regularPage).toHaveURL(/\/schedule$/);
        await expect(regularPage.getByRole('button', {name: 'Создать комнату', exact: true})).not.toBeVisible();
      } finally { await regular.close(); }

      page.once('dialog', dialog => dialog.accept());
      const deleted = page.waitForResponse(response => response.url().endsWith(`/api/v1/admin/rooms/${id}`) && response.request().method() === 'DELETE');
      await row.getByRole('button', {name: 'Удалить', exact: true}).click();
      expect((await deleted).status()).toBe(204);
      await expect(row).not.toBeVisible();
      expect((await request.get(`/api/v1/rooms/${id}`)).status()).toBe(404);
    } finally { await request.delete(`/api/v1/admin/rooms/${id}`, {headers: headers()}); }
  });

  test('member booking conflicts and ownership are enforced before cancellation in UI', async ({page, request, browser}) => {
    const suffix = Date.now().toString();
    const room = await createRoom(request, `E2E Booking ${suffix}`);
    const credentials = {email: `booking-${suffix}@e2e.test`, password: 'Test-Booking-Password-2026'};
    await page.request.post('/api/v1/auth/register', {data: credentials});
    await page.context().clearCookies();
    await login(page, credentials);
    const token = await page.evaluate(() => localStorage.getItem('authToken'));
    const auth = {Authorization: `Bearer ${token}`};
    const future = new Date(); future.setDate(future.getDate() + 7);
    if (future.getMonth() === 0 && future.getDate() === 1) future.setDate(2);
    const date = `${future.getFullYear()}-${String(future.getMonth() + 1).padStart(2, '0')}-${String(future.getDate()).padStart(2, '0')}`;
    const payload = {roomId: room.id, startTime: `${date}T10:00:00`, endTime: `${date}T11:30:00`};
    let bookingId = '';
    try {
      const created = await page.request.post('/api/v1/bookings', {headers: auth, data: payload});
      expect(created.status()).toBe(201);
      bookingId = (await created.json()).id;
      expect((await page.request.post('/api/v1/bookings', {headers: auth, data: payload})).status()).toBe(409);
      const stranger = await browser.newContext({baseURL: new URL(page.url()).origin});
      try {
        const response = await stranger.request.post('/api/v1/auth/register', {data: {email: `stranger-${suffix}@e2e.test`, password: credentials.password}});
        const strangerToken = (await response.json()).token;
        expect((await stranger.request.delete(`/api/v1/bookings/${bookingId}`, {headers: {Authorization: `Bearer ${strangerToken}`}})).status()).toBe(403);
      } finally { await stranger.close(); }
      await page.goto('/my-bookings');
      const card = page.locator('article').filter({hasText: room.name});
      await expect(card).toBeVisible();
      await card.getByRole('button', {name: 'Отменить', exact: true}).click();
      await expect(card).not.toBeVisible();
      const mine = await page.request.get('/api/v1/my-bookings', {headers: auth});
      expect((await mine.json()).find((entry: {id: string}) => entry.id === bookingId).status).toBe('CANCELLED');
    } finally {
      if (bookingId) await request.delete(`/api/v1/bookings/${bookingId}`, {headers: headers()});
      await request.delete(`/api/v1/admin/rooms/${room.id}`, {headers: headers()});
    }
  });

  test('three filters sorting pagination and deep links survive reload with real records', async ({page, request}) => {
    const rooms = [];
    const marker = `E2E Filter ${Date.now()}`;
    try {
      for (let index = 0; index < 3; index++) rooms.push(await createRoom(request, `${marker} ${index}`, 7, 10 + index));
      rooms.push(await createRoom(request, `${marker} excluded`, 8, 2));
      await login(page);
      await page.goto('/admin?size=1');
      await page.getByPlaceholder('Поиск по названию').fill(marker);
      await page.getByPlaceholder('Этаж', {exact: true}).fill('7');
      await page.getByPlaceholder('Мин. вместимость').fill('10');
      await page.getByRole('button', {name: 'Вместимость', exact: true}).click();
      await expect(page.getByRole('row').filter({hasText: `${marker} 0`})).toBeVisible();
      await page.getByRole('button', {name: 'Вперед', exact: true}).click();
      await expect(page).toHaveURL(/page=2/);
      const params = new URL(page.url()).searchParams;
      expect(params.get('floor')).toBe('7'); expect(params.get('minCapacity')).toBe('10'); expect(params.get('sort')).toBe('capacity,asc');
      await expect(page.getByRole('row').filter({hasText: `${marker} 1`})).toBeVisible();
      await page.reload();
      await expect(page.getByRole('row').filter({hasText: `${marker} 1`})).toBeVisible();
      await page.getByRole('button', {name: 'Вместимость', exact: true}).click();
      await expect(page).toHaveURL(/page=1/);
      await expect(page.getByRole('row').filter({hasText: `${marker} 2`})).toBeVisible();
    } finally {
      for (const room of rooms) await request.delete(`/api/v1/admin/rooms/${room.id}`, {headers: headers()});
    }
  });

  test('browser uploads 2 MiB file, downloads identical bytes and deletion revokes object', async ({page, request}) => {
    const room = await createRoom(request, `E2E Files ${Date.now()}`);
    const bytes = Buffer.alloc(2 * 1024 * 1024, 65);
    bytes.write('%PDF-1.4\n');
    try {
      await login(page);
      await page.goto(`/admin?search=${encodeURIComponent(room.name)}`);
      await page.getByRole('row').filter({hasText: room.name}).getByRole('button', {name: 'Файлы', exact: true}).click();
      await page.locator('input[type=file]').setInputFiles({name: 'meeting.pdf', mimeType: 'application/pdf', buffer: bytes});
      const uploaded = page.waitForResponse(response => response.url().includes(`/rooms/${room.id}/files`) && response.request().method() === 'POST');
      await page.getByRole('button', {name: 'Загрузить', exact: true}).click();
      expect((await uploaded).status()).toBe(201);
      const link = page.getByRole('link', {name: /meeting.pdf/});
      await expect(link).toBeVisible();
      const url = await link.getAttribute('href');
      expect(url).toContain('X-Amz-Signature');
      const download = await request.get(url!);
      expect(download.status()).toBe(200);
      expect(createHash('sha256').update(await download.body()).digest('hex')).toBe(createHash('sha256').update(bytes).digest('hex'));
      expect((await request.get(url!.split('?')[0])).status()).toBe(403);
      await page.locator('article').filter({has: link}).getByRole('button', {name: 'Удалить', exact: true}).click();
      await expect(link).not.toBeVisible();
      expect((await request.get(url!)).status()).toBe(404);
    } finally { await request.delete(`/api/v1/admin/rooms/${room.id}`, {headers: headers()}); }
  });
});
