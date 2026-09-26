package com.airlineprep.bot.admin;
import com.airlineprep.bot.analytics.*;
import com.airlineprep.bot.settings.SettingsService;
import com.airlineprep.bot.telegram.TelegramBotProperties;
import java.util.*;
import java.security.Principal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller @RequestMapping("/admin/analytics")
public class AnalyticsController {
 private final AnalyticsService analytics;private final SettingsService settings;private final TelegramBotProperties bot;
 public AnalyticsController(AnalyticsService a,SettingsService s,TelegramBotProperties b) {analytics=a;settings=s;bot=b;}
 @ModelAttribute("reports") List<String> reports() {return List.of("questions","categories","registrations","content","mocks","payments","students","outbox");}
 private AnalyticsService.Filter filter(Map<String,String> p) {
  return new AnalyticsService.Filter(p.get("window"),number(p.get("exam")),number(p.get("category")),p.get("status"),p.get("difficulty"),p.get("tag"),p.get("text"),p.get("sort"),number(p.get("student")));
 }
 private Long number(String value) {return value==null||value.isBlank()?null:Long.valueOf(value);}
 private void common(Model m,AnalyticsService.Filter f,String report) {m.addAttribute("filter",f);m.addAttribute("report",report);}
 @GetMapping
 String overview(@RequestParam Map<String,String> p,Model m) {
  var f=filter(p);common(m,f,"");m.addAttribute("metrics",analytics.overview(f));
  m.addAttribute("operations",settings.current());m.addAttribute("transport",bot.enabled()?bot.mode().name():"DISABLED");return "admin/analytics";
 }
 @GetMapping("/{report:questions|categories|registrations|content|mocks|payments|students|outbox}")
 String report(@PathVariable String report,@RequestParam Map<String,String> p,@RequestParam(defaultValue="0") int page,Model m) {
  var f=filter(p);common(m,f,report);m.addAttribute("data",analytics.report(report,f,page,25));return "admin/analytics-report";
 }
 @GetMapping("/questions/{id}")
 String question(@PathVariable long id,@RequestParam Map<String,String> p,@RequestParam(defaultValue="0") int page,Model m) {
  var f=filter(p);common(m,f,"Question version options #"+id);m.addAttribute("data",analytics.options(id,f,page));return "admin/analytics-detail";
 }
 @GetMapping("/students/{id}")
 String student(@PathVariable long id,@RequestParam(defaultValue="0") int page,Model m) {
  m.addAttribute("report","Student #"+id);m.addAttribute("details",analytics.student(id));m.addAttribute("data",analytics.studentMocks(id,page));return "admin/analytics-detail";
 }
 @PostMapping("/maintenance")
 String maintenance(@RequestParam boolean enabled,Principal actor) {settings.maintenance(enabled,actor.getName());return "redirect:/admin/analytics";}
 @GetMapping("/{report:questions|categories|registrations|content|mocks|payments}/export.csv")
 void export(@PathVariable String report,@RequestParam Map<String,String> p,HttpServletResponse response) throws IOException {
  var data=analytics.report(report,filter(p),0,500);
  response.setContentType("text/csv;charset=UTF-8");response.setHeader("Content-Disposition","attachment; filename=\""+report+"-analytics.csv\"");
  response.setHeader("Cache-Control","no-store");response.setHeader("X-Export-Row-Limit","500");response.setHeader("X-Export-Truncated",Boolean.toString(data.more()));
  Writer writer=new OutputStreamWriter(response.getOutputStream(),StandardCharsets.UTF_8);
  writer.write('\uFEFF');SafeCsv.row(writer,data.headers());for(var row:data.rows()) SafeCsv.row(writer,row);writer.flush();
 }
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
 String invalid(Model m) {m.addAttribute("status",400);m.addAttribute("error","Invalid analytics filter or page. Use the report controls.");return "error";}
}
