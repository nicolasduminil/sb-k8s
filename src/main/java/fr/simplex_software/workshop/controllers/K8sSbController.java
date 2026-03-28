package fr.simplex_software.workshop.controllers;

import org.springframework.web.bind.annotation.*;

@RestController
public class K8sSbController
{
  @GetMapping("/hello/{who}")
  public String sayHello(@PathVariable String who)
  {
    return "Hello %s".formatted(who);
  }
}
