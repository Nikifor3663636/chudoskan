using System.Text;
using ChudoScan.Print.Core;
using Xunit;

namespace ChudoScan.Print.Tests;

public class SupabaseWireTests
{
    private static string Jwt(string payloadJson)
    {
        static string B64(string s) => Convert.ToBase64String(Encoding.UTF8.GetBytes(s)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        return $"{B64("{\"alg\":\"RS256\"}")}.{B64(payloadJson)}.sig";
    }

    [Fact]
    public void ByteaFromPostgrest()
    {
        Assert.Equal(new byte[] { 1, 2, 0xff }, SupabaseWire.Bytea("\\x0102ff"));
        Assert.Null(SupabaseWire.Bytea("AQI="));
        Assert.Null(SupabaseWire.Bytea("\\xzz"));
        Assert.Null(SupabaseWire.Bytea(null));
    }

    [Fact]
    public void RoleClaim()
    {
        Assert.True(SupabaseWire.HasRole(Jwt("{\"sub\":\"pc1\",\"role\":\"authenticated\"}")));
        Assert.False(SupabaseWire.HasRole(Jwt("{\"sub\":\"pc1\"}")));
        Assert.False(SupabaseWire.HasRole(Jwt("{\"role\":\"service_role\"}")));
        Assert.False(SupabaseWire.HasRole("not-a-jwt"));
    }

    [Fact]
    public void JoinOnlyOwnJobs()
    {
        var m = SupabaseWire.JoinMessage("realtime:print_jobs-pc1", "pc1", "tok", "1");
        Assert.Equal("phx_join", m["event"]!.GetValue<string>());
        var pc = m["payload"]!["config"]!["postgres_changes"]![0]!;
        Assert.Equal("print_jobs", pc["table"]!.GetValue<string>());
        Assert.Equal("agent_id=eq.pc1", pc["filter"]!.GetValue<string>());
        Assert.Equal("tok", m["payload"]!["access_token"]!.GetValue<string>());
    }

    [Fact]
    public void ChangeMessages()
    {
        Assert.True(SupabaseWire.IsChange("{\"event\":\"postgres_changes\",\"payload\":{}}"));
        Assert.False(SupabaseWire.IsChange("{\"event\":\"phx_reply\",\"payload\":{}}"));
        Assert.False(SupabaseWire.IsChange("не json"));
    }
}
