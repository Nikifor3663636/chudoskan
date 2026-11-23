using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace ChudoScan.Print;

/// <summary>Правила Firestore відмовили (PERMISSION_DENIED): ПК відключено від магазину.</summary>
public sealed class PermissionDeniedException(string message) : Exception(message);

/// <summary>Документ Firestore: шлях, поля (REST-значення) і час зміни для оптимістичного запису.</summary>
public sealed record FsDoc(string Name, JsonObject Fields, string? UpdateTime)
{
    public string Id => Name[(Name.LastIndexOf('/') + 1)..];
    public string? Str(string f) => Fields[f]?["stringValue"]?.GetValue<string>();
    public long? Int(string f) => Fields[f]?["integerValue"] is { } v ? long.Parse(v.GetValue<string>(), CultureInfo.InvariantCulture) : null;
    public DateTimeOffset? Time(string f) => Fields[f]?["timestampValue"] is { } v ? DateTimeOffset.Parse(v.GetValue<string>(), CultureInfo.InvariantCulture) : null;
    public byte[]? Bytes(string f) => Fields[f]?["bytesValue"] is { } v ? Convert.FromBase64String(v.GetValue<string>()) : null;
}

/// <summary>
/// Firestore через REST з ID-токеном Firebase («Authorization: Bearer») — діють ті самі правила, що й для
/// застосунку. Таймаут запиту — 20 с: довше — «Немає зв'язку».
/// </summary>
public sealed class FirestoreGateway(HttpClient http, FirebaseAuthClient auth, AppSettings settings)
{
    private string Base => $"https://firestore.googleapis.com/v1/projects/{settings.ProjectId}/databases/(default)/documents";

    public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(20);

    // ---------- Значення ----------

    public static JsonObject S(string v) => new() { ["stringValue"] = v };
    public static JsonObject B(bool v) => new() { ["booleanValue"] = v };
    public static JsonObject I(long v) => new() { ["integerValue"] = v.ToString(CultureInfo.InvariantCulture) };
    public static JsonObject T(DateTimeOffset v) => new() { ["timestampValue"] = v.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture) };
    public static JsonObject A(IEnumerable<JsonObject> values) => new() { ["arrayValue"] = new JsonObject { ["values"] = new JsonArray(values.Cast<JsonNode>().ToArray()) } };
    public static JsonObject M(JsonObject fields) => new() { ["mapValue"] = new JsonObject { ["fields"] = fields } };

    // ---------- Запити ----------

    /// <summary>Документ або null (немає). Відмова правил — <see cref="PermissionDeniedException"/>.</summary>
    public async Task<FsDoc?> GetAsync(string path, CancellationToken ct)
    {
        using var r = await SendAsync(HttpMethod.Get, $"{Base}/{path}", null, ct, allowNotFound: true);
        if (r.StatusCode == HttpStatusCode.NotFound) return null;
        return ToDoc(JsonNode.Parse(await r.Content.ReadAsStringAsync(ct))!.AsObject());
    }

    /// <summary>Створити документ з id; вже існує — false.</summary>
    public async Task<bool> CreateAsync(string collection, string id, JsonObject fields, CancellationToken ct)
    {
        using var r = await SendAsync(HttpMethod.Post, $"{Base}/{collection}?documentId={Uri.EscapeDataString(id)}",
            new JsonObject { ["fields"] = fields }, ct, allowConflict: true);
        return r.StatusCode != HttpStatusCode.Conflict;
    }

    /// <summary>Записати документ повністю (як set) — для agents/{uid}.</summary>
    public async Task SetAsync(string path, JsonObject fields, CancellationToken ct)
    {
        using var _ = await SendAsync(HttpMethod.Patch, $"{Base}/{path}", new JsonObject { ["fields"] = fields }, ct);
    }

    public async Task DeleteAsync(string path, CancellationToken ct)
    {
        using var _ = await SendAsync(HttpMethod.Delete, $"{Base}/{path}", null, ct, allowNotFound: true);
    }

    /// <summary>printJobs, де agentId == uid (і status == …), до [limit].</summary>
    public async Task<IReadOnlyList<FsDoc>> JobsAsync(string uid, string? status, int limit, CancellationToken ct)
    {
        var filters = new JsonArray { FieldEq("agentId", S(uid)) };
        if (status != null) filters.Add(FieldEq("status", S(status)));
        var query = new JsonObject
        {
            ["structuredQuery"] = new JsonObject
            {
                ["from"] = new JsonArray { new JsonObject { ["collectionId"] = "printJobs" } },
                ["where"] = filters.Count == 1 ? filters[0]!.DeepClone() : new JsonObject
                {
                    ["compositeFilter"] = new JsonObject { ["op"] = "AND", ["filters"] = filters },
                },
                ["limit"] = limit,
            },
        };
        using var r = await SendAsync(HttpMethod.Post, $"{Base}:runQuery", query, ct);
        var list = new List<FsDoc>();
        foreach (var item in JsonNode.Parse(await r.Content.ReadAsStringAsync(ct))!.AsArray())
            if (item?["document"] is JsonObject d) list.Add(ToDoc(d));
        return list;
    }

    /// <summary>
    /// Змінити поля документа, лише якщо він не змінився з [updateTime] (як транзакція: друге вікно або
    /// інший ПК не візьме те саме завдання). serverTime — поля з часом сервера. false — документ уже змінено.
    /// </summary>
    public Task<bool> UpdateAsync(string path, JsonObject fields, IEnumerable<string> serverTime, string? updateTime, CancellationToken ct,
        IReadOnlyDictionary<string, long>? increment = null) =>
        CommitAsync(path, fields, serverTime, updateTime, replace: false, ct, increment);

    /// <summary>Записати документ повністю (як set), поля [serverTime] — часом сервера. Для пульсу agents/{uid}.</summary>
    public async Task SetWithServerTimeAsync(string path, JsonObject fields, IEnumerable<string> serverTime, CancellationToken ct)
    {
        if (!await CommitAsync(path, fields, serverTime, null, replace: true, ct)) throw new HttpRequestException("Firestore: запис не прийнято");
    }

    private async Task<bool> CommitAsync(string path, JsonObject fields, IEnumerable<string> serverTime, string? updateTime, bool replace,
        CancellationToken ct, IReadOnlyDictionary<string, long>? increment = null)
    {
        var name = $"projects/{settings.ProjectId}/databases/(default)/documents/{path}";
        var transforms = new JsonArray();
        foreach (var f in serverTime) transforms.Add(new JsonObject { ["fieldPath"] = f, ["setToServerValue"] = "REQUEST_TIME" });
        foreach (var (f, by) in increment ?? new Dictionary<string, long>()) transforms.Add(new JsonObject { ["fieldPath"] = f, ["increment"] = I(by) });
        var write = new JsonObject
        {
            ["update"] = new JsonObject { ["name"] = name, ["fields"] = fields },
            ["updateTransforms"] = transforms,
        };
        if (!replace)
        {
            write["updateMask"] = new JsonObject { ["fieldPaths"] = new JsonArray(fields.Select(kv => (JsonNode)JsonValue.Create(kv.Key)!).ToArray()) };
            write["currentDocument"] = updateTime != null ? new JsonObject { ["updateTime"] = updateTime } : new JsonObject { ["exists"] = true };
        }
        using var r = await SendAsync(HttpMethod.Post, $"{Base}:commit", new JsonObject { ["writes"] = new JsonArray { write } }, ct, allowConflict: true);
        return r.IsSuccessStatusCode;
    }

    private static JsonObject FieldEq(string field, JsonObject value) => new()
    {
        ["fieldFilter"] = new JsonObject { ["field"] = new JsonObject { ["fieldPath"] = field }, ["op"] = "EQUAL", ["value"] = value },
    };

    private static FsDoc ToDoc(JsonObject d) =>
        new(d["name"]!.GetValue<string>(), d["fields"]?.AsObject() is { } f ? (JsonObject)f.DeepClone() : new JsonObject(), d["updateTime"]?.GetValue<string>());

    private async Task<HttpResponseMessage> SendAsync(HttpMethod method, string url, JsonObject? body, CancellationToken ct,
        bool allowNotFound = false, bool allowConflict = false)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(Timeout);
        var token = await auth.TokenAsync(timeout.Token);
        var req = new HttpRequestMessage(method, url);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (body != null) req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        var r = await http.SendAsync(req, timeout.Token);
        if (r.IsSuccessStatusCode) return r;
        if (allowNotFound && r.StatusCode == HttpStatusCode.NotFound) return r;
        // Невдалий commit з умовою currentDocument — FAILED_PRECONDITION (400) або ABORTED (409).
        if (allowConflict && (r.StatusCode == HttpStatusCode.Conflict || r.StatusCode == HttpStatusCode.BadRequest && url.EndsWith(":commit", StringComparison.Ordinal))) return r;
        var text = await r.Content.ReadAsStringAsync(ct);
        r.Dispose();
        if (r.StatusCode == HttpStatusCode.Forbidden || text.Contains("PERMISSION_DENIED", StringComparison.Ordinal))
            throw new PermissionDeniedException("Немає доступу (ПК не підключено до магазину)");
        throw new HttpRequestException($"Firestore: HTTP {(int)r.StatusCode}");
    }
}
